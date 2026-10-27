#!/usr/bin/env python3
"""Freeze experimental weighted V3 decisions over complete plain TS VOD ranges.

Original snapshots and media are never edited. Missing cues score zero using the
packaged scorer. Unsupported media and exhausted budgets are explicitly unknown.
"""
import argparse
from collections import OrderedDict
from contextlib import contextmanager, nullcontext
import copy
import hashlib
import json
import math
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import tempfile
import time
import uuid

import hls_ad_filter as ads
from ad_analysis_checkpoint import CandidateJournal

POLICY = ads.WEIGHTED_POLICY
OCR_ENGINE = 'mlkit-chinese-16.0.1'
# The Android speed/coverage tradeoff is separate from the frozen host experiment.
OCR_SAMPLING_PROFILE = {'name': 'front-two-960-v1', 'candidate_fractions': [.05, .1],
                        'context_fractions': [.5], 'max_width': 960}
FEATURE_SUMMARY_PROFILE = 'weighted_decision'
OCR_EVALUATION_POLICY = 'candidate-score-upper-bound-v1'
OCR_BOUND_SCORER_POLICY = 'weighted-v3-layout-development'
MAX_CANDIDATES = 4096
MAX_CANDIDATE_BYTES = 256 * 1024 * 1024
MAX_CANDIDATE_SEGMENTS = 1024
MAX_CONTEXT_ENTRIES = 64
MAX_REGION_FILES = 8
MAX_OCR_ENTRIES = 512
MAX_AUDIT_BYTES = 64 * 1024 * 1024
# Match EightMapleHlsAds.Files.readResult; diagnostics must fit with the decision.
MAX_PLAN_BYTES = 4 * 1024 * 1024


class WallTimeProfile:
    """Exclusive elapsed waits, not CPU time; nested operations charge only their leaf."""
    CATEGORIES = ('cache_fetch', 'remux', 'ffprobe_metadata', 'full_av_decode',
        'frame_extraction', 'ocr_bridge_wait', 'scene_audio_features', 'pure_scoring',
        'plan_checkpoint_io', 'known_catalog', 'unattributed')

    def __init__(self, clock=None):
        self.clock = clock or time.monotonic
        self.started = self.last = self.clock()
        self.stack = []
        self.seconds = dict.fromkeys(self.CATEGORIES, 0.0)
        self.calls = dict.fromkeys(self.CATEGORIES, 0)

    def _charge(self):
        now = self.clock()
        self.seconds[self.stack[-1] if self.stack else 'unattributed'] += max(0, now - self.last)
        self.last = now

    @contextmanager
    def measure(self, category):
        if category not in self.seconds:
            raise ValueError('Unknown timing category')
        self._charge()
        # A high-level frame extraction can contain a native call in the same
        # category. It remains one operation, with no duplicated time or count.
        if not self.stack or self.stack[-1] != category:
            self.calls[category] += 1
        self.stack.append(category)
        try:
            yield
        finally:
            self._charge()
            self.stack.pop()

    def call(self, category, function, *args, **kwargs):
        with self.measure(category):
            return function(*args, **kwargs)

    @staticmethod
    def native_category(command):
        if '-show_streams' in command or '-show_format' in command:
            return 'ffprobe_metadata'
        if 'copy' in command and '-c' in command:
            return 'remux'
        if 'png' in command:
            return 'frame_extraction'
        if 'rawvideo' in command or 's16le' in command:
            return 'full_av_decode'
        return 'unattributed'

    @contextmanager
    def instrument(self, probe, scorer, cache):
        originals = []

        def patch(owner, name, category=None):
            original = getattr(owner, name)
            def measured(*args, **kwargs):
                kind = category or self.native_category(args[0])
                return self.call(kind, original, *args, **kwargs)
            originals.append((owner, name, original))
            setattr(owner, name, measured)

        try:
            patch(cache, 'fetch', 'cache_fetch')
            patch(FeatureScanner, 'remux', 'remux')
            patch(probe, 'run')
            for name in ('load_slice', 'visual_summary', 'audio_summary', 'bridge_evidence'):
                patch(probe, name, 'scene_audio_features')
            for name in ('extract_png', 'extract_png_batch'):
                patch(probe, name, 'frame_extraction')
            patch(scorer, 'score_case', 'pure_scoring')
            patch(ads, 'atomic_json', 'plan_checkpoint_io')
            yield
        finally:
            for owner, name, original in reversed(originals):
                setattr(owner, name, original)

    def snapshot(self):
        self._charge()
        total = max(0, self.last - self.started)
        return {'schema': 1, 'clock': 'monotonic_wall', 'exclusive': True,
            'total_seconds': total, 'known_catalog_seconds': self.seconds['known_catalog'],
            'weighted_only_seconds': total - self.seconds['known_catalog'],
            'reconciliation_error_seconds': total - sum(self.seconds.values()),
            'categories': {name: {'seconds': self.seconds[name], 'calls': self.calls[name]}
                           for name in self.CATEGORIES},
            'scope': 'analysis_start_through_plan_validation_before_final_plan_publication',
            'not_measured': ['CPU time', 'policy loading and module imports',
                             'final frozen-plan publication', 'retained-media download and validation'],
            'notes': {'cache_fetch': 'cache lookup/read/hash, network and throttling waits',
                'full_av_decode': 'native video and audio decode subprocess elapsed waits',
                'scene_audio_features': 'slice preparation/coverage validation, visual/audio summaries and bridge computation; native waits excluded',
                'ocr_bridge_wait': 'Android OCR request publication and result polling; cache hits excluded',
                'plan_checkpoint_io': 'candidate journal restore/commit and atomic diagnostic/evidence writes; final plan publication excluded'}}


def feature_modules():
    # Android packages these stable module names from the unmodified host tools.
    import probe_hls_ad_features
    import score_hls_ad_features
    return probe_hls_ad_features, score_hls_ad_features


def load_policy(path, known):
    raw = path.read_bytes()
    try:
        value = json.loads(raw)
        if (value.get('schema') != 1 or value.get('policy') != POLICY
                or type(value.get('known_catalog_enabled')) is not bool
                or value['known_catalog_enabled'] != known or value.get('ocr_engine') != OCR_ENGINE
                or not isinstance(value.get('assets'), dict)):
            raise ads.FilterError('Invalid weighted policy')
        required = {'weighted_hls_ads.py', 'ad_analysis_checkpoint.py', 'hls_ad_filter.py', 'hls_resume.py', 'hls_media_validation.py',
                    'probe_hls_ad_features.py', 'score_hls_ad_features.py', 'hls_ad_fingerprints.json'}
        if not required <= set(value['assets']):
            raise ads.FilterError('Incomplete weighted runtime policy')
        for name, sha in value['assets'].items():
            target = path.parent / name
            if (not isinstance(name, str) or Path(name).name != name or target.is_symlink()
                    or not ads.HEX.fullmatch(str(sha)) or ads.digest(target.read_bytes()) != sha):
                raise ads.FilterError('Weighted runtime asset changed: ' + str(name))
    except (ValueError, TypeError, AttributeError):
        raise ads.FilterError('Invalid weighted policy') from None
    return value, ads.digest(raw)


def read_input(path):
    try:
        value = json.loads(path.read_text(encoding='utf-8'))
        if (not isinstance(value, dict) or type(value.get('schema')) is not int or value['schema'] != 1
                or not ads.HEX.fullmatch(str(value.get('identity', '')))
                or not isinstance(value.get('manifest'), str) or not ads.http_url(value.get('url'))
                or not isinstance(value.get('headers', {}), dict)
                or type(value.get('known_catalog_enabled')) is not bool):
            raise ads.FilterError('Invalid weighted input')
        for key, text in value.get('headers', {}).items():
            if (not isinstance(key, str) or not re.fullmatch(r"[!#$%&'*+.^_`|~0-9A-Za-z-]+", key)
                    or not isinstance(text, str) or '\n' in text or '\r' in text):
                raise ads.FilterError('Invalid weighted request headers')
        rate = value.get('rate_limit_bytes_per_second')
        if rate is not None and (type(rate) is not int or rate <= 0):
            raise ads.FilterError('Invalid weighted rate limit')
        return value
    except (ValueError, TypeError, AttributeError):
        raise ads.FilterError('Invalid weighted input') from None


def candidates(regions, media_limit=None):
    """Every nonempty adjacent complete range <=60s, without semantic preselection.

    media_limit controls only whether media/boundary objects accompany an identity
    descriptor; population order and identity do not depend on this allocation cap.
    """
    ordinal = 0
    for start, first in enumerate(regions):
        duration, segments, segment_indices = 0, [], []
        for end in range(start, len(regions)):
            region = regions[end]
            if not region['segments']:
                break
            duration += region['duration_seconds']
            if duration > ads.MAX_SECONDS + 1e-9:
                break
            segment_indices.extend(segment['index'] for segment in region['segments'])
            group = {'region_indices': list(range(start, end + 1)),
                     'segment_indices': list(segment_indices), 'duration_seconds': duration}
            if media_limit is None or ordinal < media_limit:
                segments.extend(region['segments'])
                group.update(segments=list(segments),
                             start_boundary=first['start_boundary'], end_boundary=region['end_boundary'])
            yield group
            ordinal += 1


def candidate_population(regions):
    """Hash the full canonical descriptor array while retaining a bounded prefix.

    URL/media/boundary content is already bound by the journal's source manifest
    and request identity. No media objects or full population list are retained.
    """
    digest = hashlib.sha256()
    digest.update(b'[')
    prefix, count = [], 0
    for descriptor in candidates(regions, media_limit=0):
        if count:
            digest.update(b',')
        digest.update(ads.canonical(descriptor))
        if count < MAX_CANDIDATES:
            prefix.append(descriptor)
        count += 1
    digest.update(b']')
    return prefix, count, digest.hexdigest()


def require_supported_video_streams(streams):
    if sorted(stream.get('codec_name', '') for stream in streams) != ['aac', 'h264']:
        raise ads.Unknown('requires_requested_streams:aac,h264')


def limit_plan_diagnostics(plan):
    if len(ads.canonical(plan)) <= MAX_PLAN_BYTES:
        return
    rows, plan['regions'] = plan['regions'], []
    plan['regions_omitted'] = len(rows)
    remaining = MAX_PLAN_BYTES - len(ads.canonical(plan))
    for row in rows:
        size = len(ads.canonical(row)) + 1
        if size > remaining:
            break
        plan['regions'].append(row)
        remaining -= size
    plan['regions_omitted'] -= len(plan['regions'])


def select_groups(rows, known=()):
    selected = [dict(group) for group in known]
    occupied = {s for group in selected for s in group['segment_indices']}
    passing = [row for row in rows if row.get('decision') == 'experimental_ad_candidate']
    passing.sort(key=lambda row: (-row['score'], row['duration_seconds'], row['region_indices']))
    for row in passing:
        overlaps = [g['region_indices'] for g in selected if set(g['segment_indices']) & set(row['segment_indices'])]
        row['overlapping_selected_ranges'] = overlaps
        if occupied.intersection(row['segment_indices']):
            row['selection'] = 'preserved_overlapping_higher_ranked_range'
        else:
            group = {key: row[key] for key in ('region_indices', 'segment_indices', 'duration_seconds')}
            group.update(score=row['score'], reason='weighted_threshold')
            selected.append(group)
            occupied.update(row['segment_indices'])
            row['selection'] = 'selected'
    for row in rows:
        row.setdefault('selection', 'preserved_unknown')
        row['overlapping_passing_ranges'] = [other['region_indices'] for other in passing
            if other is not row and set(other['segment_indices']) & set(row['segment_indices'])]
    return sorted(selected, key=lambda group: group['region_indices'])


def candidate_ocr_bound(features, scorer):
    """Prove retention without candidate OCR; never substitute an estimated score.

    The frozen scorer adds S+A+C and subtracts nonnegative text penalties. At
    most one time group per requested OCR fraction can contribute to C. Context
    OCR remains eager, retaining its existing errors, cache and file lifetimes.
    """
    if (scorer is None or scorer.POLICY != OCR_BOUND_SCORER_POLICY or features['errors']
            or features['candidate_gate'] != 'valid'
            or features['inputs']['candidate'].get('status') != 'ok'):
        return None
    settings = scorer.SETTINGS
    expected = settings['ocr_expected_frames']
    fractions = OCR_SAMPLING_PROFILE['candidate_fractions']
    values = (expected, settings['weights']['P'], settings['domain_share'],
              settings['semantic_share'], settings['layout_max'],
              settings['content_cap'], settings['threshold'],
              settings['programme_negative_max'], settings['overlay_negative_max'],
              settings['ocr_confidence_power'], settings['commercial_semantic_power'])
    if (any(type(value) not in (int, float) or not math.isfinite(value) or value < 0
            for value in values) or expected <= 0
            or not fractions or any(type(value) not in (int, float)
                or not math.isfinite(value) or not 0 <= value <= 1 for value in fractions)):
        return None
    av = scorer.scene_score(features)['points'] + scorer.audio_score(features)['points']
    if not math.isfinite(av) or av < 0:
        return None
    frame_share = len(fractions) / expected
    text_max = settings['weights']['P'] * min(1, frame_share) * (
        settings['domain_share'] + settings['semantic_share'])
    content_max = min(settings['content_cap'], text_max + settings['layout_max'] * frame_share)
    nominal_upper = min(100, av + content_max)
    margin = 1e-9 * max(1, nominal_upper, settings['threshold'])
    upper = min(100, nominal_upper + margin)
    if upper >= settings['threshold']:
        return None
    return {'policy': OCR_EVALUATION_POLICY, 'status': 'skipped_below_threshold',
            'av_points': av, 'remaining_content_upper_bound': content_max,
            'score_upper_bound': upper, 'threshold': settings['threshold'],
            'numeric_margin': margin, 'candidate_frames_max': len(fractions),
            'score_is_exact': False}


class AnalysisProgress:
    """Complete snapshots, also suitable for restored checkpoint counters/time."""
    def __init__(self, completed=0, total=None, unknown=0, ads_count=None, elapsed_seconds=0,
                 checkpoint_reason=None):
        self.completed, self.total, self.unknown, self.ads_count = completed, total, unknown, ads_count
        self.elapsed_seconds, self.started = elapsed_seconds, time.monotonic()
        self.checkpoint_reason = checkpoint_reason

    def emit(self, stage):
        snapshot = {'stage': stage, 'completed': self.completed,
            'total': self.total, 'unknown': self.unknown, 'ads': self.ads_count,
            'elapsed_seconds': self.elapsed_seconds + time.monotonic() - self.started}
        if self.checkpoint_reason:
            snapshot['checkpoint_reason'] = self.checkpoint_reason
        print('HLS_AD_PROGRESS ' + json.dumps(snapshot, separators=(',', ':')), flush=True)


class OCRBridge:
    def __init__(self, root, probe, progress=None, profiler=None):
        self.root, self.probe = root, probe
        self.progress = progress
        self.profiler = profiler
        self.results = OrderedDict()
        self.failures = OrderedDict()
        self.requests = self.reused = 0

    def __call__(self, png, command=None):
        key = ads.digest(png)
        if key in self.failures:
            self.reused += 1
            raise self.probe.Unknown(self.failures[key])
        if key in self.results:
            self.reused += 1
            result = self.results.pop(key)
            self.results[key] = result
            return copy.deepcopy(result)
        token = uuid.uuid4().hex
        image = self.root / ('ocr-' + token + '.png')
        result = self.root / ('ocr-' + token + '.json')
        self.requests += 1
        try:
            if self.progress:
                self.progress.emit('ocr')
            with image.open('xb') as output:
                output.write(png)
            with self.profiler.measure('ocr_bridge_wait') if self.profiler else nullcontext():
                print('HLS_AD_OCR ' + token, flush=True)
                deadline = time.monotonic() + 30
                while not result.exists():
                    if time.monotonic() >= deadline:
                        raise self.probe.Unknown('ocr_bridge_timeout')
                    time.sleep(.05)
            if result.is_symlink() or result.stat().st_size > 1024 * 1024:
                raise self.probe.Unknown('invalid_ocr_bridge_result')
            data = self.probe.validate_ocr(json.loads(result.read_text(encoding='utf-8')))
            if data['engine'] != OCR_ENGINE:
                raise self.probe.Unknown('unexpected_ocr_engine')
            self.results[key] = data
            if len(self.results) > MAX_OCR_ENTRIES:
                self.results.popitem(last=False)
            return copy.deepcopy(data)
        except (self.probe.Unknown, OSError, ValueError, TypeError) as exc:
            reason = str(exc) if isinstance(exc, self.probe.Unknown) else 'ocr_bridge_failed:' + str(exc)
            self.failures[key] = reason
            if len(self.failures) > MAX_OCR_ENTRIES:
                self.failures.popitem(last=False)
            raise self.probe.Unknown(reason) from None
        finally:
            image.unlink(missing_ok=True)
            result.unlink(missing_ok=True)


class FeatureScanner:
    def __init__(self, root, work, cache, regions, ffmpeg, ffprobe, probe, progress=None, scorer=None):
        self.root, self.work, self.cache, self.regions = root, work, cache, regions
        self.ffmpeg, self.ffprobe, self.probe = ffmpeg, ffprobe, probe
        self.progress = progress
        self.scorer = scorer
        self.context = OrderedDict()
        self.context_reuse = 0
        self.region_files = OrderedDict()
        self.region_remux_reuse = self.region_remux_count = 0

    def _prune_regions(self, protected=()):
        for key in list(self.region_files):
            if len(self.region_files) <= MAX_REGION_FILES:
                break
            if key not in protected:
                self.region_files.pop(key).unlink(missing_ok=True)

    def _region_file(self, segments):
        key = tuple((s['index'], s['url'], s['duration_seconds']) for s in segments)
        if key in self.region_files:
            self.region_remux_reuse += 1
            target = self.region_files.pop(key)
            self.region_files[key] = target
            return key, target
        entries, total = [], 0
        if self.progress:
            self.progress.emit('source')
        for segment in segments:
            entry = self.cache.fetch(segment['url'], MAX_CANDIDATE_BYTES - total)
            entries.append(entry)
            total += entry['size']
        token = uuid.uuid4().hex
        playlist = self.work / ('region-' + token + '.m3u8')
        target = self.work / ('region-' + token + '.ts')
        lines = ['#EXTM3U', '#EXT-X-TARGETDURATION:%d' % math.ceil(max(s['duration_seconds'] for s in segments))]
        for segment, entry in zip(segments, entries):
            lines += ['#EXTINF:%s,' % segment['duration_seconds'], str(ads.cache_path(self.root, entry))]
        lines.append('#EXT-X-ENDLIST')
        playlist.write_text('\n'.join(lines) + '\n', encoding='utf-8')
        try:
            self.probe.run([self.ffmpeg, '-nostdin', '-v', 'error', '-xerror', '-y',
                '-protocol_whitelist', 'file', '-i', str(playlist), '-map', '0:v:0', '-map', '0:a:0?',
                '-c', 'copy', '-f', 'mpegts', str(target)])
        except BaseException:
            target.unlink(missing_ok=True)
            raise
        finally:
            playlist.unlink(missing_ok=True)
        self.region_remux_count += 1
        self.region_files[key] = target
        return key, target

    def remux(self, segments, name, region_starts=()):
        if len(segments) > MAX_CANDIDATE_SEGMENTS:
            raise ads.Unknown('candidate_segment_budget')
        # Preserve the original continuity clock within each region. Only true
        # discontinuities get the concat demuxer's new presentation timeline.
        pieces = []
        for segment in segments:
            if not pieces or segment['index'] in region_starts:
                pieces.append([])
            pieces[-1].append(segment)
        active, files = [], []
        listing, target = None, None
        try:
            for piece in pieces:
                key, path = self._region_file(piece)
                active.append(key)
                files.append(path)
                # Active candidate files are pinned until concat finishes; at
                # rest the region-file LRU holds at most eight entries.
                self._prune_regions(active)
            if sum(path.stat().st_size for path in files) > MAX_CANDIDATE_BYTES:
                raise ads.Unknown('candidate_byte_budget')
            if len(files) == 1:
                return files[0]
            listing = self.work / (name + '.ffconcat')
            target = self.work / (name + '.ts')
            listing.write_text('ffconcat version 1.0\n' + ''.join('file ' + path.name + '\n' for path in files),
                               encoding='utf-8')
            self.probe.run([self.ffmpeg, '-nostdin', '-v', 'error', '-xerror', '-y',
                '-protocol_whitelist', 'file', '-f', 'concat', '-safe', '0', '-i', str(listing),
                '-map', '0:v:0', '-map', '0:a:0?', '-c', 'copy', '-f', 'mpegts', str(target)])
            return target
        except BaseException:
            if target:
                target.unlink(missing_ok=True)
            raise
        finally:
            if listing:
                listing.unlink(missing_ok=True)
            self._prune_regions(active if len(files) == 1 else ())

    def release(self, path):
        if path and path not in self.region_files.values():
            path.unlink(missing_ok=True)

    @staticmethod
    def source_timing(media, nominal):
        media['public']['source_timing'] = {
            'nominal_extinf_seconds': nominal,
            'actual_source_duration_seconds': media['public'].get('metadata', {}).get('format', {}).get('duration'),
            'actual_slice_start_seconds': media.get('start'),
            'actual_slice_duration_seconds': media.get('duration')}

    def outside(self, index, role):
        if not 0 <= index < len(self.regions):
            return None, {'status': 'absent', 'reason': 'playlist_edge', 'samples': []}
        key = (index, role)
        if key in self.context:
            self.context_reuse += 1
            item = self.context.pop(key)
            self.context[key] = item
            return item[:2]
        segments, duration = [], 0
        ordered = self.regions[index]['segments']
        for segment in (reversed(ordered) if role == 'before' else ordered):
            segments.append(segment)
            duration += segment['duration_seconds']
            if duration >= self.probe.POLICY_SETTINGS['context_seconds']:
                break
        if role == 'before':
            segments.reverse()
        path = None
        try:
            if not segments:
                raise ads.Unknown('empty_context_region')
            path = self.remux(segments, 'context-%d-%s' % key)
            item = self.probe.load_slice({'path': str(path)}, role,
                                         self.work, self.ffmpeg, self.ffprobe,
                                         summary_mode=FEATURE_SUMMARY_PROFILE)
            self.source_timing(item, duration)
            ocr = self.probe.extract_ocr(item, role, self.ffmpeg, None,
                fractions=OCR_SAMPLING_PROFILE['context_fractions'],
                max_width=OCR_SAMPLING_PROFILE['max_width'])
        except (ads.Unknown, self.probe.Unknown, ValueError, KeyError, TypeError, OSError) as exc:
            item = None
            ocr = {'status': 'unknown', 'reason': str(exc), 'samples': []}
        self.context[key] = (item, ocr, path)
        if len(self.context) > MAX_CONTEXT_ENTRIES:
            _, (_, _, old_path) = self.context.popitem(last=False)
            self.release(old_path)
        return item, ocr

    def process(self, group):
        media, errors, ocr = {}, [], {}
        expected_absences = []
        path = None
        try:
            starts = {r['segments'][0]['index'] for r in self.regions if r['segments']}
            path = self.remux(group['segments'], 'candidate', starts)
            if self.progress:
                self.progress.emit('probe')
            media['candidate'] = self.probe.load_slice({'path': str(path)},
                'candidate', self.work, self.ffmpeg, self.ffprobe,
                summary_mode=FEATURE_SUMMARY_PROFILE)
            require_supported_video_streams(media['candidate']['public']['metadata'].get('streams', []))
            self.source_timing(media['candidate'], group['duration_seconds'])
        except (ads.Unknown, self.probe.Unknown, ValueError, KeyError, TypeError, OSError) as exc:
            media['candidate'] = None
            errors.append('candidate:' + str(exc))
        try:
            for role, index in [('before', group['region_indices'][0] - 1), ('after', group['region_indices'][-1] + 1)]:
                media[role], ocr[role] = self.outside(index, role)
                if media[role] is None:
                    if ocr[role].get('status') == 'absent' and ocr[role].get('reason') == 'playlist_edge':
                        expected_absences.append(role)
                    else:
                        errors.append(role + ':' + ocr[role].get('reason', 'invalid_context'))
            for role, result in ocr.items():
                if role in expected_absences:
                    continue
                if result.get('reason'):
                    errors.append(role + ':ocr:' + result['reason'])
                errors.extend(role + ':ocr:' + sample['reason'] for sample in result.get('samples', []) if sample.get('reason'))
            features = {'id': '%d-%d' % (group['region_indices'][0], group['region_indices'][-1]),
                'inputs': {r: m['public'] if m else {
                    'status': 'absent' if r in expected_absences else 'unknown',
                    'reason': 'playlist_edge' if r in expected_absences else 'decode_failed'} for r, m in media.items()},
                'errors': errors, 'ocr': ocr, 'expected_absences': expected_absences,
                'ocr_sampling': dict(OCR_SAMPLING_PROFILE),
                'feature_summary_profile': FEATURE_SUMMARY_PROFILE,
                'bridge_only_S': self.probe.bridge_evidence(media, {'start': group['start_boundary'], 'end': group['end_boundary']}),
                'candidate_gate': 'valid' if media['candidate'] else 'unknown'}
            bound = candidate_ocr_bound(features, self.scorer)
            if bound:
                ocr['candidate'] = {'status': 'skipped', 'samples': [],
                                    'reason_code': 'score_upper_bound_below_threshold'}
                features['ocr_evaluation'] = bound
            else:
                ocr['candidate'] = self.probe.extract_ocr(media['candidate'], 'candidate', self.ffmpeg, None,
                    batch=True, fractions=OCR_SAMPLING_PROFILE['candidate_fractions'],
                    max_width=OCR_SAMPLING_PROFILE['max_width'])
                features['ocr_evaluation'] = {'policy': OCR_EVALUATION_POLICY,
                    'status': 'evaluated', 'score_is_exact': True}
                result = ocr['candidate']
                if result.get('reason'):
                    errors.append('candidate:ocr:' + result['reason'])
                errors.extend('candidate:ocr:' + sample['reason'] for sample in result.get('samples', []) if sample.get('reason'))
            return features
        finally:
            self.release(path)


def check_cached(plan, root, original):
    try:
        _, _, retained = ads.parse_manifest(plan['manifest'], original['url'])
    except ads.Unknown:
        retained = []
    urls, seen = {s['url'] for s in retained}, set()
    for entry in plan['cached_segments']:
        ads.cache_path(root, entry)
        if entry['url'] not in urls or entry['url'] in seen:
            raise ads.FilterError('Unexpected weighted cached URL')
        seen.add(entry['url'])
    if sum(e['size'] for e in plan['cached_segments']) > ads.plan_cache_budget(plan):
        raise ads.FilterError('Weighted plan cache byte budget')


def classify(input_path, catalog_path, policy_path, plan_path, ffmpeg, ffprobe):
    input_path, catalog_path, policy_path, plan_path = map(Path, (input_path, catalog_path, policy_path, plan_path))
    root = input_path.parent
    if (any(not p.is_absolute() for p in (input_path, catalog_path, policy_path, plan_path, Path(ffmpeg), Path(ffprobe)))
            or root.name != '.resume' or root.is_symlink() or plan_path.parent != root
            or input_path.is_symlink() or plan_path.is_symlink()):
        raise ads.FilterError('Weighted input and plan must share a real absolute resume directory')
    original = read_input(input_path)
    policy, policy_sha = load_policy(policy_path, original['known_catalog_enabled'])
    if policy.get('assets') and ads.digest(catalog_path.read_bytes()) != policy['assets'].get('hls_ad_fingerprints.json'):
        raise ads.FilterError('Supplied catalog does not match frozen weighted policy')
    # A frozen plan wins before importing feature tools, OCR, or any network.
    if plan_path.exists():
        plan = ads.validate_plan(json.loads(plan_path.read_text(encoding='utf-8')), original, policy_sha)
        if plan['policy'] != POLICY:
            raise ads.FilterError('Wrong frozen weighted policy')
        check_cached(plan, root, original)
        progress = AnalysisProgress(completed=plan['coverage']['eligible_candidates'],
            total=plan['coverage']['eligible_candidates'],
            unknown=plan['coverage']['unknown_candidates'], ads_count=plan['skipped_ads'],
            elapsed_seconds=plan.get('cost', {}).get('elapsed_seconds', 0))
        progress.emit('restoring')
        progress.emit('complete')
        return plan
    probe, scorer = feature_modules()
    sampling = {**OCR_SAMPLING_PROFILE,
        'planned_candidate_frames': len(OCR_SAMPLING_PROFILE['candidate_fractions']),
        'scorer_expected_candidate_frames': scorer.SETTINGS['ocr_expected_frames'],
        'temporal_persistence_min_distinct_times': probe.POLICY_SETTINGS['ocr_min_distinct_times']}
    profiler = WallTimeProfile()
    started = profiler.started
    try:
        raw, regions, segments = ads.parse_manifest(original['manifest'], original['url'])
        unsupported = None
    except ads.Unknown as exc:
        raw, regions, segments, unsupported = [], [], [], str(exc)
    candidate_prefix, candidate_total, population_sha = candidate_population(regions)
    journal = CandidateJournal(root, original, policy_sha, candidate_prefix,
        score_threshold=scorer.SETTINGS['threshold'],
        population_count=candidate_total, population_sha256=population_sha)
    restored = profiler.call('plan_checkpoint_io', journal.restore)
    reused = restored['completed']
    saved_elapsed = restored['saved_elapsed_seconds']
    rows = [record['payload']['row'] for record in restored['records']]
    reason = restored['invalidation_reason']
    reason_code = None
    if reason:
        if reason.startswith('checkpoint_invalid_candidate_'):
            reason_code = 'checkpoint_candidate_invalid'
        elif reason.startswith('checkpoint_missing_candidate_'):
            reason_code = 'checkpoint_candidate_missing'
        else:
            reason_code = reason
    progress = AnalysisProgress(completed=reused,
        total=candidate_total if unsupported is None else None,
        unknown=sum(row['status'] == 'unknown' or any(':ocr:' in error for error in row['errors']) for row in rows),
        elapsed_seconds=saved_elapsed, checkpoint_reason=reason_code)
    progress.started = started
    if reused or reason:
        progress.emit('restoring')
    progress.emit('source')
    commit_boundary = started
    cache = ads.SegmentCache(root, original['identity'], original.get('headers', {}),
        rate_limit=original.get('rate_limit_bytes_per_second'), max_total_bytes=ads.WEIGHTED_MAX_TOTAL_BYTES)
    known, known_error, known_cost = [], None, {}
    if original['known_catalog_enabled'] and segments:
        # Legacy exact catalog remains a separate, independently frozen decision.
        try:
            old_report = ads._report_progress
            # The nested legacy scan has a different population. Its progress
            # must not replace restored weighted counters or elapsed time.
            ads._report_progress = lambda *args, **kwargs: progress.emit('source')
            try:
                known_plan = profiler.call('known_catalog', ads.classify,
                    input_path, catalog_path, root / 'known-plan.json', ffmpeg, ffprobe)
            finally:
                ads._report_progress = old_report
            known_cost = known_plan.get('cost', {})
            for r in regions:
                if r['segments'] and all(s['index'] in known_plan['skipped_segment_indices'] for s in r['segments']):
                    known.append({'region_indices': [r['index']], 'segment_indices': [s['index'] for s in r['segments']],
                                  'duration_seconds': r['duration_seconds'], 'reason': 'exact_catalog'})
            # Refresh complete entries fetched by the independent known scan.
            cache = ads.SegmentCache(root, original['identity'], original.get('headers', {}),
                rate_limit=original.get('rate_limit_bytes_per_second'), max_total_bytes=ads.WEIGHTED_MAX_TOTAL_BYTES)
        except (ads.FilterError, OSError, ValueError) as exc:
            known_error = str(exc)
    bridge, old_ocr = OCRBridge(root, probe, progress, profiler), probe.ocr_png
    probe.ocr_png = bridge
    audits, audit_bytes, eligible, scanned = [], 0, reused, reused
    audit_exhausted = False

    def retain_audit(row, features, weighted):
        nonlocal audit_bytes, audit_exhausted
        audit = {'range': row['region_indices'], 'features': features, 'weighted': weighted}
        size = len(ads.canonical(audit))
        if not audit_exhausted and audit_bytes + size <= MAX_AUDIT_BYTES:
            audits.append(audit)
            audit_bytes += size
        else:
            audit_exhausted = True
            row['audit_reason'] = 'raw_feature_audit_byte_budget'

    for record, row in zip(restored['records'], rows):
        payload = record['payload']
        retain_audit(row, payload['features'], payload['weighted'])
    progress.emit('scan')
    print('HLS_AD_STAGE scan', flush=True)
    try:
        with profiler.instrument(probe, scorer, cache), tempfile.TemporaryDirectory(prefix='.weighted-', dir=root) as directory:
            scanner = FeatureScanner(root, Path(directory), cache, regions, ffmpeg, ffprobe, probe, progress, scorer)
            for ordinal, group in enumerate(candidates(regions, media_limit=MAX_CANDIDATES)):
                if ordinal < reused:
                    continue
                eligible += 1
                row = {key: group[key] for key in ('region_indices', 'segment_indices', 'duration_seconds')}
                row.update(status='unknown', decision='unknown', score=0, errors=[], expected_absence_count=0)
                if eligible > MAX_CANDIDATES:
                    row['errors'] = ['candidate_count_budget']
                else:
                    progress.emit('probe')
                    print('HLS_AD_STAGE probe', flush=True)
                    features = scanner.process(group)
                    scored = scorer.score_case(features)
                    evaluation = features.get('ocr_evaluation', {})
                    scored['score_is_exact'] = evaluation.get('score_is_exact', True)
                    if not scored['score_is_exact']:
                        scored['score_upper_bound'] = evaluation['score_upper_bound']
                    row.update(score=scored['score'], decision=scored['decision'], errors=features['errors'],
                               expected_absence_count=len(features.get('expected_absences', [])),
                               status='scored' if scored['candidate_source_gate']['valid'] else 'unknown',
                               ocr_evaluation=copy.deepcopy(evaluation), score_is_exact=scored['score_is_exact'])
                    retain_audit(row, features, scored)
                    boundary = time.monotonic()
                    profiler.call('plan_checkpoint_io', journal.commit,
                        ordinal, features, scored, row, boundary - commit_boundary)
                    commit_boundary = boundary
                    scanned += 1
                    latest = {'schema': 1, 'snapshot_identity': original['identity'], 'catalog_sha256': policy_sha,
                              'ocr_sampling': sampling,
                              'feature_summary_profile': FEATURE_SUMMARY_PROFILE,
                              'ocr_evaluation_policy': OCR_EVALUATION_POLICY,
                              'completed_index': scanned, 'total': min(MAX_CANDIDATES, candidate_total),
                              'range': row['region_indices'], 'row': row, 'features': features, 'weighted': scored}
                    if len(ads.canonical(latest)) > MAX_AUDIT_BYTES:
                        latest.pop('features')
                        latest.pop('weighted')
                        latest['error'] = 'latest_case_audit_byte_budget'
                    # Diagnostic only: never read to classify or resume a job.
                    ads.atomic_json(root / 'weighted-latest.json', latest)
                rows.append(row)
                progress.completed = eligible
                progress.unknown += int(row['status'] == 'unknown' or any(':ocr:' in error for error in row['errors']))
                progress.emit('scan')
                print('HLS_AD_CANDIDATE %d %d' % (eligible, candidate_total), flush=True)
            context_reuse = scanner.context_reuse
            region_remux_reuse, region_remux_count = scanner.region_remux_reuse, scanner.region_remux_count
    finally:
        probe.ocr_png = old_ocr
    groups = select_groups(rows, known)
    skipped = {index for group in groups for index in group['segment_indices']}
    if segments and len(skipped) == len(segments):
        groups, skipped = [], set()
        for row in rows:
            if row['selection'] == 'selected':
                row['selection'] = 'preserved_would_remove_all_media'
    retained = {s['url'] for s in segments if s['index'] not in skipped}
    bounded_ocr = sum(row.get('ocr_evaluation', {}).get('status') == 'skipped_below_threshold' for row in rows)
    profiler.call('plan_checkpoint_io', ads.atomic_json,
        root / 'weighted-features.json', {'schema': 1, 'policy': POLICY, 'catalog_sha256': policy_sha,
        'snapshot_identity': original['identity'], 'ocr_sampling': sampling,
        'feature_summary_profile': FEATURE_SUMMARY_PROFILE, 'ocr_evaluation_policy': OCR_EVALUATION_POLICY,
        'cases': audits,
        'audit_truncated': len(audits) < scanned, 'raw_feature_bytes': audit_bytes})
    ocr_failures = sum(sum(':ocr:' in error for error in row['errors']) for row in rows)
    source_unknown = sum(row['status'] == 'unknown' for row in rows)
    plan = {'schema': 1, 'policy': POLICY, 'catalog_sha256': policy_sha, 'snapshot_identity': original['identity'],
        'manifest': ads.filtered_manifest(raw, segments, skipped) if segments else original['manifest'],
        'groups': groups, 'skipped_segment_indices': sorted(skipped), 'skipped_ads': len(groups),
        'skipped_ad_segments': len(skipped), 'skipped_ad_seconds': sum(g['duration_seconds'] for g in groups),
        'cached_segments': [entry for url, entry in cache.entries.items() if url in retained], 'regions': rows,
        'unsupported_reason': unsupported, 'known_catalog_error': known_error, 'ocr_sampling': sampling,
        'feature_summary_profile': FEATURE_SUMMARY_PROFILE, 'ocr_evaluation_policy': OCR_EVALUATION_POLICY,
        'selection_policy': 'score_descending_duration_ascending_then_region_indices_disjoint',
        'selection_ambiguity': any(row.get('overlapping_passing_ranges') for row in rows),
        'coverage': {'source_regions': len(regions), 'eligible_candidates': eligible, 'candidates_scanned': scanned,
                     'unknown_candidates': progress.unknown,
                     'reduced_ocr_sampling': sampling['planned_candidate_frames'] < sampling['scorer_expected_candidate_frames'],
                     'candidate_ocr_skipped_by_score_bound': bounded_ocr,
                     'candidate_ocr_evidence_complete': unsupported is None and eligible == scanned
                         and source_unknown == 0 and bounded_ocr == 0 and ocr_failures == 0,
                     'unscanned_candidates': eligible - scanned,
                     'unknown_candidate_sources': source_unknown,
                     'valid_candidate_sources': sum(row['status'] == 'scored' for row in rows), 'ocr_failure_count': ocr_failures,
                     'expected_absence_count': sum(row['expected_absence_count'] for row in rows),
                     'enumeration_complete': unsupported is None,
                     'decoded_population_complete': unsupported is None and eligible == scanned and source_unknown == 0,
                     'analysis_complete': unsupported is None and eligible == scanned and source_unknown == 0 and ocr_failures == 0,
                     'complete': unsupported is None and eligible == scanned and all(row['status'] == 'scored' for row in rows) and ocr_failures == 0
                                 and all(r['segments'] and r['duration_seconds'] <= 60 for r in regions),
                     'out_of_scope_regions': [{'index': r['index'], 'reason': 'duration_above_60_seconds' if r['duration_seconds'] > 60 else 'empty_region'}
                                             for r in regions if not r['segments'] or r['duration_seconds'] > 60]},
        'budgets': {'cache_bytes': ads.WEIGHTED_MAX_TOTAL_BYTES, 'candidate_bytes': MAX_CANDIDATE_BYTES,
                    'candidate_segments': MAX_CANDIDATE_SEGMENTS, 'candidates': MAX_CANDIDATES, 'raw_audit_bytes': MAX_AUDIT_BYTES,
                    'region_files_at_rest': MAX_REGION_FILES},
        'cost': {'network_bytes': cache.network_bytes + known_cost.get('network_bytes', 0),
                 'known_catalog_cost': known_cost, 'cache_reuse_count': cache.reused_segments + known_cost.get('cache_reuse_count', 0),
                 'cache_reuse_bytes': cache.reused_bytes + known_cost.get('cache_reuse_bytes', 0), 'context_reuse_count': context_reuse,
                 'ocr_requests': bridge.requests, 'ocr_png_reuse_count': bridge.reused,
                 'candidates_scanned': scanned, 'region_remux_count': region_remux_count,
                 'region_remux_reuse_count': region_remux_reuse,
                 'checkpoint': {'reused_candidates': reused, 'new_candidates': scanned - reused,
                     'reason': reason_code, 'reason_detail': reason,
                     'saved_elapsed_seconds': saved_elapsed,
                     'committed_elapsed_seconds': journal.elapsed,
                     'storage': 'analysis-checkpoint', 'raw_evidence_complete': bounded_ocr == 0,
                     'recorded_evidence_complete': True},
                 'elapsed_seconds': saved_elapsed + time.monotonic() - started}}
    plan['identity'] = ads.plan_identity(plan)
    ads.validate_plan(plan, original, policy_sha)
    check_cached(plan, root, original)
    timing = profiler.snapshot()
    timing['known_catalog_reported_elapsed_seconds'] = known_cost.get('elapsed_seconds')
    timing['reuse'] = {key: plan['cost'][key] for key in ('network_bytes', 'cache_reuse_count',
        'cache_reuse_bytes', 'context_reuse_count', 'ocr_requests', 'ocr_png_reuse_count',
        'candidates_scanned', 'region_remux_count', 'region_remux_reuse_count')}
    timing['reuse']['candidates_scanned'] = scanned - reused
    timing['reuse_scope'] = 'current_attempt_including_known_catalog'
    timing['attempt_scope'] = 'current_attempt_only_excludes_saved_checkpoint_elapsed'
    timing['checkpoint_reused_candidates'] = reused
    timing['candidates_processed_current_attempt'] = scanned - reused
    timing['weighted_cache'] = {'network_bytes': cache.network_bytes,
        'cache_reuse_count': cache.reused_segments, 'cache_reuse_bytes': cache.reused_bytes}
    plan['cost'].update(elapsed_seconds=saved_elapsed + timing['total_seconds'],
        cumulative_elapsed_seconds=saved_elapsed + timing['total_seconds'],
        current_attempt_elapsed_seconds=timing['total_seconds'], timing=timing)
    limit_plan_diagnostics(plan)
    try:
        ads.atomic_json(plan_path, plan, replace=False)
    except FileExistsError:
        return classify(input_path, catalog_path, policy_path, plan_path, ffmpeg, ffprobe)
    progress.ads_count = plan['skipped_ads']
    progress.emit('complete')
    return plan


def download_validation_matches(path, expected, session_path, ffmpeg, streams, audio_only=False):
    """Reuse a complete source decode only after binding it to final encoded A/V.

    Both normalized elementary streams retain decoder configuration. Timing is
    checked independently on the final container, never inferred from hashes.
    Unavailable/uncertain evidence falls back to decoding the final file.
    """
    if audio_only or session_path is None:
        return False, 'not_enabled'
    try:
        session_path = Path(session_path)
        receipt_path = session_path.parent / 'download-validation.json'
        if (not session_path.is_absolute() or session_path.parent.name != '.resume'
                or session_path.is_symlink() or receipt_path.is_symlink()
                or not 0 < session_path.stat().st_size <= 8 * 1024 * 1024
                or not 0 < receipt_path.stat().st_size <= 65536):
            return False, 'invalid_receipt_files'
        session = json.loads(session_path.read_text())
        receipt = json.loads(receipt_path.read_text())
        if not isinstance(session, dict) or not isinstance(receipt, dict):
            return False, 'invalid_receipt_metadata'
        config = session['media_validation']
        manifest = session['manifest']
        if not isinstance(manifest, str):
            return False, 'invalid_receipt_metadata'
        durations = [float(line.strip()[8:].split(',', 1)[0]) for line in manifest.splitlines()
                     if line.strip().startswith('#EXTINF:')]
        if (session.get('schema') != 1 or not isinstance(config, dict) or config.get('schema') != 1
                or config.get('media_mode') != 'video' or not config.get('token')
                or receipt.get('schema') != 1 or receipt.get('status') != 'valid'
                or receipt.get('identity') != session['identity']
                or receipt.get('token') != config['token'] or receipt.get('media_mode') != 'video'
                or receipt.get('eof') is not True or receipt.get('full_decode') is not True
                or not durations or not math.isfinite(sum(durations))
                or abs(sum(durations) - expected) > 1e-6
                or type(receipt.get('segments')) is not int or receipt['segments'] != len(durations)
                or type(receipt.get('source_bytes')) is not int or receipt['source_bytes'] <= 0
                or not re.fullmatch(r'[0-9a-f]{64}', receipt.get('source_sha256', ''))):
            return False, 'incomplete_or_stale_receipt'
        # Other codecs, transcoding and unusual AAC profiles retain the existing
        # final decoder. Strict equality is never relaxed to improve the hit rate.
        audio = next(stream for stream in streams if stream.get('codec_type') == 'audio')
        if audio.get('profile') != 'LC' or audio.get('channels') not in (1, 2):
            return False, 'unsupported_audio_configuration'
        from hls_media_validation import fingerprint_file, proof_identity
        if any(receipt.get(key) != value for key, value in proof_identity(ffmpeg).items()):
            return False, 'validator_or_engine_changed'
        fingerprints = receipt.get('fingerprints')
        if not isinstance(fingerprints, dict) or set(fingerprints) != {'video', 'audio', 'video_timing'}:
            return False, 'invalid_fingerprints'
        for name in ('video', 'audio', 'video_timing'):
            value = fingerprints[name]
            size_key = 'packets' if name == 'video_timing' else 'bytes'
            if (not isinstance(value, dict) or type(value.get(size_key)) is not int or value[size_key] <= 0
                    or not re.fullmatch(r'[0-9a-f]{64}', value.get('sha256', ''))):
                return False, 'invalid_fingerprints'
        if fingerprint_file(path, ffmpeg) != fingerprints:
            return False, 'encoded_content_mismatch_or_unavailable'
        return True, 'complete_decode_and_encoded_content_match'
    except (OSError, ValueError, KeyError, TypeError, StopIteration, ImportError, subprocess.SubprocessError):
        return False, 'receipt_unavailable'


def validate_media(path, expected, result_path, ffmpeg, ffprobe, audio_only=False, session_path=None):
    """Validate the complete retained output before publication, never edit it."""
    path, result_path = Path(path), Path(result_path)
    if (not path.is_absolute() or not result_path.is_absolute() or path.is_symlink()
            or not Path(ffmpeg).is_absolute() or not Path(ffprobe).is_absolute()
            or not math.isfinite(expected) or expected <= 0):
        raise ads.FilterError('Invalid retained media validation arguments')
    before = path.stat()
    result = {'schema': 1, 'status': 'unknown', 'expected_duration_seconds': expected,
              'media_size': before.st_size, 'media_mtime_ns': before.st_mtime_ns,
              'media_mode': 'audio' if audio_only else 'video'}
    try:
        data = ads.run_json([ffprobe, '-v', 'error', '-show_streams', '-show_format', '-of', 'json', str(path)])
        streams = data.get('streams', [])
        expected_stream_count = 1 if audio_only else 2
        if audio_only:
            if len(streams) != 1 or streams[0].get('codec_type') != 'audio':
                raise ads.Unknown('requires_exactly_one_audio_stream_and_no_video')
        else:
            require_supported_video_streams(streams)
        actual = float(data['format']['duration'])
        if not math.isfinite(actual) or abs(actual - expected) > 1:
            raise ads.Unknown('retained_duration_mismatch')
        # Check source packet DTS independently; only audio null-muxer clocks are normalized.
        with tempfile.TemporaryFile() as diagnostics:
            process = subprocess.Popen([ffprobe, '-v', 'error', '-show_packets', '-show_entries',
                'packet=stream_index,dts_time', '-of', 'csv=p=0', str(path)], stdout=subprocess.PIPE,
                stderr=diagnostics, text=True)
            try:
                previous = {}
                for line in process.stdout:
                    values = line.strip().split(',')
                    if len(values) < 2 or not values[0].isdigit():
                        continue
                    index, timestamp = int(values[0]), float(values[1])
                    if not math.isfinite(timestamp) or timestamp <= previous.get(index, -math.inf):
                        raise ads.Unknown('non_monotonic_retained_packet_dts')
                    previous[index] = timestamp
                if process.wait(timeout=120) or len(previous) != expected_stream_count:
                    raise ads.Unknown('retained_packet_probe_failed')
                diagnostics.seek(0)
                if diagnostics.read(1):
                    raise ads.Unknown('retained_packet_probe_warning')
            finally:
                if process.poll() is None:
                    process.kill()
                process.wait()
                process.stdout.close()
        reused, reuse_reason = download_validation_matches(path, expected, session_path, ffmpeg, streams, audio_only)
        result.update(decode_method='during_download' if reused else 'after_download',
                      download_validation_reason=reuse_reason, final_full_av_decode=not reused)
        if not reused:
            decode = [ffmpeg, '-nostdin', '-v', 'error', '-xerror', '-i', str(path)]
            if not audio_only:
                # Keep decoded video PTS: a frame counter restarts when metadata rebuilds the graph.
                decode += ['-map', '0:v:0', '-vsync', '0']
            decode += ['-map', '0:a:0', '-af', 'asetpts=N/SR/TB', '-f', 'null', '-']
            decoded = subprocess.run(decode, capture_output=True, timeout=3600)
            if decoded.returncode or decoded.stderr.strip():
                raise ads.Unknown('retained_full_av_decode_failed:' + decoded.stderr.decode('utf-8', 'replace')[-1200:])
        after = path.stat()
        if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
            raise ads.Unknown('retained_media_changed_during_validation')
        result.update(status='valid', actual_duration_seconds=actual, full_av_decode=True, packet_dts_monotonic=True)
    except (ads.Unknown, OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as exc:
        result['reason'] = str(exc)
    ads.atomic_json(result_path, result)
    if result['status'] != 'valid':
        raise ads.FilterError('Retained media validation failed: ' + result.get('reason', 'unknown'))
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    for name in ('input', 'catalog', 'policy', 'plan', 'validate-media', 'validation-result', 'validation-session'):
        parser.add_argument('--' + name)
    for name in ('ffmpeg', 'ffprobe'):
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--expected-duration', type=float)
    parser.add_argument('--audio-only', action='store_true')
    args = parser.parse_args(argv)
    signal.signal(signal.SIGTERM, lambda *_: ads.sys_exit_cancelled())
    if args.validate_media:
        if args.expected_duration is None or not args.validation_result:
            parser.error('validation requires --expected-duration and --validation-result')
        return validate_media(args.validate_media, args.expected_duration, args.validation_result,
                              args.ffmpeg, args.ffprobe, args.audio_only, args.validation_session)
    if not all((args.input, args.catalog, args.policy, args.plan)):
        parser.error('scan requires --input --catalog --policy --plan')
    return classify(args.input, args.catalog, args.policy, args.plan, args.ffmpeg, args.ffprobe)


if __name__ == '__main__':
    try:
        main()
    except (ads.FilterError, OSError, ValueError) as exc:
        print('ERROR: media-dler weighted HLS: ' + str(exc), file=sys.stderr, flush=True)
        sys.exit(1)
