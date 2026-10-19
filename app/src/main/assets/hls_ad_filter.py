#!/usr/bin/env python3
"""Freeze content-confirmed HLS ad decisions before native HLS checkpoints exist.

Discontinuities identify complete candidates, never ads. Unknown content stays.
Complete cache entries are disposable; a committed plan is immutable for a job.
"""
import argparse
import hashlib
from http.client import IncompleteRead
import json
import math
import os
from pathlib import Path
import re
import signal
import subprocess
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.parse import unquote, urljoin, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

POLICY = 'eightmaple-content-v1'
NORMALIZER = 'h264-aac-complete-packet-sequences-v1'
MAX_SECONDS = 60
MAX_SEGMENTS = 128
MAX_REGIONS = 16
MAX_BYTES = 32 * 1024 * 1024
MAX_TOTAL_BYTES = 128 * 1024 * 1024
HEX = re.compile(r'[0-9a-f]{64}')
MARKER = '# ppvod-ad-injected v1'
METADATA = {'#EXT-X-VERSION', '#EXT-X-TARGETDURATION', '#EXT-X-MEDIA-SEQUENCE',
            '#EXT-X-DISCONTINUITY-SEQUENCE', '#EXT-X-ALLOW-CACHE'}
ALLOWED = {'#EXT-X-INDEPENDENT-SEGMENTS', '#EXT-X-KEY:METHOD=NONE', '#EXT-X-PLAYLIST-TYPE:VOD'}


class FilterError(RuntimeError):
    pass


class Unknown(RuntimeError):
    pass


class TransientFetch(Unknown):
    """A complete transaction may be retried once before freezing unknown."""
    pass


def digest(data):
    return hashlib.sha256(data).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode('utf-8')


def plan_identity(plan):
    return digest(canonical({key: plan[key] for key in (
        'policy', 'catalog_sha256', 'snapshot_identity', 'skipped_segment_indices', 'manifest')}))


def atomic_json(path, value, replace=True):
    path = Path(path)
    descriptor, temporary = tempfile.mkstemp(prefix=path.name + '.', suffix='.tmp', dir=path.parent)
    try:
        with os.fdopen(descriptor, 'w', encoding='utf-8') as stream:
            json.dump(value, stream, ensure_ascii=False, separators=(',', ':'))
            stream.flush()
            os.fsync(stream.fileno())
        if replace:
            os.replace(temporary, path)
        else:
            # DownloadRunner owns this job directory exclusively. Android's
            # bundled Python omits os.link; refuse collisions before rename.
            if os.path.lexists(path):
                raise FileExistsError(str(path))
            os.rename(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def http_url(url):
    try:
        p = urlsplit(url)
        return (p.scheme in ('http', 'https') and bool(p.hostname) and p.username is None
                and p.password is None and '#' not in url and not any(c.isspace() or ord(c) < 32 for c in url)
                and p.port != 0)
    except (ValueError, TypeError):
        return False


def parse_manifest(manifest, base_url):
    """Return complete regions and original line indices; never normalize output."""
    if not isinstance(manifest, str) or not http_url(base_url):
        raise Unknown('invalid_manifest_or_base_url')
    raw_lines = manifest.splitlines(keepends=True)
    lines = [(i, line.strip()) for i, line in enumerate(raw_lines) if line.strip()]
    if not lines or lines[0][1] != '#EXTM3U' or lines[-1][1] != '#EXT-X-ENDLIST':
        raise Unknown('not_complete_plain_ts_vod')
    regions, segments = [], []
    region = {'index': 0, 'start_boundary': 'playlist_start', 'segments': [], 'marker_hint': False}
    pending, marker = None, False
    for position, (lineno, line) in enumerate(lines[1:], 1):
        if line.startswith('#EXTINF:'):
            value, comma, _ = line[8:].partition(',')
            try:
                duration = float(value)
            except ValueError:
                raise Unknown('invalid_extinf') from None
            if pending is not None or not comma or not math.isfinite(duration) or duration <= 0:
                raise Unknown('invalid_extinf')
            pending = (lineno, duration)
        elif line in ('#EXT-X-DISCONTINUITY', '#EXT-X-ENDLIST'):
            if pending is not None or (line == '#EXT-X-ENDLIST' and position != len(lines) - 1):
                raise Unknown('invalid_region_boundary')
            region['end_boundary'] = 'discontinuity' if line == '#EXT-X-DISCONTINUITY' else 'playlist_end'
            region['duration_seconds'] = sum(s['duration_seconds'] for s in region['segments'])
            regions.append(region)
            region = {'index': len(regions), 'start_boundary': region['end_boundary'], 'segments': [], 'marker_hint': False}
        elif line == MARKER:
            marker = True
        elif line in ALLOWED or line.split(':', 1)[0] in METADATA:
            continue
        elif line.startswith(('#EXT', '#ANVATO', '#UPLYNK')):
            raise Unknown('unsupported_manifest_tag')
        elif line.startswith('#'):
            continue
        else:
            resolved = urljoin(base_url, line)
            if pending is None or not http_url(resolved) or not unquote(urlsplit(resolved).path).lower().endswith('.ts'):
                raise Unknown('invalid_ts_reference')
            inf_line, duration = pending
            segment = {'index': len(segments), 'url': resolved, 'duration_seconds': duration,
                       'line_indices': [inf_line, lineno]}
            segments.append(segment)
            region['segments'].append(segment)
            region['marker_hint'] |= marker
            pending, marker = None, False
    if pending is not None or not segments:
        raise Unknown('incomplete_or_empty_manifest')
    return raw_lines, regions, segments


def filtered_manifest(raw_lines, segments, skipped):
    omitted = {line for segment in segments if segment['index'] in skipped for line in segment['line_indices']}
    return ''.join(line for index, line in enumerate(raw_lines) if index not in omitted)


def validate_fingerprint(fp):
    if not isinstance(fp, dict) or fp.get('normalizer') != NORMALIZER or set(fp.get('streams', {})) != {'video', 'audio'}:
        raise FilterError('Invalid fingerprint normalizer or streams')
    for kind, fields in [('video', {'codec_name', 'profile', 'level', 'width', 'height', 'pix_fmt', 'extradata_hash'}),
                         ('audio', {'codec_name', 'profile', 'sample_rate', 'channels', 'channel_layout', 'extradata_hash'})]:
        stream = fp['streams'][kind]
        if (not isinstance(stream, dict) or set(stream) != {'metadata', 'packet_count', 'packet_sequence_sha256'}
                or type(stream.get('packet_count')) is not int or stream['packet_count'] <= 0
                or not HEX.fullmatch(str(stream.get('packet_sequence_sha256', '')))
                or not isinstance(stream.get('metadata'), dict) or set(stream['metadata']) != fields
                or stream['metadata'].get('codec_name') != ('h264' if kind == 'video' else 'aac')
                or not re.fullmatch(r'SHA256:[0-9a-f]{64}', str(stream['metadata'].get('extradata_hash', '')))):
            raise FilterError('Invalid complete stream fingerprint')
        metadata = stream['metadata']
        numeric = ('level', 'width', 'height') if kind == 'video' else ('channels',)
        text = ('profile', 'pix_fmt') if kind == 'video' else ('profile', 'channel_layout')
        if (any(type(metadata[key]) is not int or metadata[key] <= 0 for key in numeric)
                or any(not isinstance(metadata[key], str) or not metadata[key] for key in text)
                or kind == 'audio' and (not isinstance(metadata['sample_rate'], str)
                    or not metadata['sample_rate'].isdigit() or int(metadata['sample_rate']) <= 0)):
            raise FilterError('Incomplete codec parameters')
    return fp


def read_catalog(path):
    raw = Path(path).read_bytes()
    try:
        catalog = json.loads(raw)
        if (not isinstance(catalog, dict) or type(catalog.get('schema')) is not int or catalog['schema'] != 1
                or catalog.get('policy') != POLICY or catalog.get('normalizer') != NORMALIZER
                or not isinstance(catalog.get('signatures'), list) or not catalog['signatures']):
            raise FilterError('Unsupported ad fingerprint catalog')
        labels = set()
        for signature in catalog['signatures']:
            if not isinstance(signature.get('label'), str) or not signature['label'] or signature['label'] in labels:
                raise FilterError('Invalid fingerprint label')
            labels.add(signature['label'])
            validate_fingerprint(signature['fingerprint'])
    except (ValueError, KeyError, TypeError, AttributeError):
        raise FilterError('Invalid ad fingerprint catalog') from None
    return catalog, digest(raw)


def run_json(command):
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=120, check=True)
        # Diagnostic stderr is conservative unknown, even on exit zero.
        if result.stderr.strip():
            raise Unknown('probe_warning')
        return json.loads(result.stdout)
    except (OSError, subprocess.SubprocessError, ValueError) as exc:
        raise Unknown('probe_failed:' + type(exc).__name__) from None


def fingerprint(path, ffprobe):
    data = run_json([ffprobe, '-v', 'error', '-show_streams', '-show_packets',
                     '-show_data_hash', 'sha256', '-of', 'json', str(path)])
    streams = data.get('streams', [])
    video = [s for s in streams if s.get('codec_type') == 'video']
    audio = [s for s in streams if s.get('codec_type') == 'audio']
    if len(streams) != 2 or len(video) != 1 or len(audio) != 1:
        raise Unknown('requires_exactly_one_video_and_audio')
    result = {'normalizer': NORMALIZER, 'streams': {}}
    for kind, stream, fields in [
        ('video', video[0], ('codec_name', 'profile', 'level', 'width', 'height', 'pix_fmt', 'extradata_hash')),
        ('audio', audio[0], ('codec_name', 'profile', 'sample_rate', 'channels', 'channel_layout', 'extradata_hash')),
    ]:
        hashes = [p.get('data_hash', '') for p in data.get('packets', []) if p.get('stream_index') == stream['index']]
        if not hashes or any(not re.fullmatch(r'SHA256:[0-9a-fA-F]{64}', h) for h in hashes):
            raise Unknown('missing_packet_hashes')
        ordered = ''.join(h.split(':', 1)[1].lower() + '\n' for h in hashes)
        metadata = {field: stream.get(field) for field in fields}
        result['streams'][kind] = {'metadata': metadata, 'packet_count': len(hashes),
                                   'packet_sequence_sha256': digest(ordered.encode('ascii'))}
    try:
        return validate_fingerprint(result)
    except FilterError:
        raise Unknown('unsupported_codec_or_metadata') from None


def same_content(left, right):
    return left.get('normalizer') == right.get('normalizer') == NORMALIZER and left == right


def cache_path(root, entry):
    """Validate metadata/path without requiring optional disposable cache payload."""
    if (not isinstance(entry, dict) or not http_url(entry.get('url'))
            or type(entry.get('size')) is not int or not 0 < entry['size'] <= MAX_BYTES
            or not HEX.fullmatch(str(entry.get('sha256', '')))):
        raise FilterError('Invalid cached segment metadata')
    expected = 'ad-cache/' + digest(entry['url'].encode('utf-8')) + '.ts'
    if entry.get('path') != expected:
        raise FilterError('Unsafe cached segment path')
    root = Path(root)
    path = root / expected
    if root.is_symlink() or (root / 'ad-cache').is_symlink() or path.is_symlink() or path.parent.resolve() != root.resolve() / 'ad-cache':
        raise FilterError('Cached segments must not use symbolic links')
    return path


def valid_cached(root, entry):
    path = cache_path(root, entry)
    try:
        return path if path.is_file() and path.stat().st_size == entry['size'] and digest(path.read_bytes()) == entry['sha256'] else None
    except OSError:
        return None


def validate_plan(plan, original=None, catalog_sha256=None):
    """Validate a committed plan; never repair/recompute its skip decision."""
    if (not isinstance(plan, dict) or type(plan.get('schema')) is not int or plan['schema'] != 1
            or plan.get('policy') != POLICY
            or any(not HEX.fullmatch(str(plan.get(key, ''))) for key in ('identity', 'snapshot_identity', 'catalog_sha256'))
            or not isinstance(plan.get('manifest'), str)
            or not isinstance(plan.get('skipped_segment_indices'), list)
            or any(type(index) is not int or index < 0 for index in plan['skipped_segment_indices'])
            or plan['skipped_segment_indices'] != sorted(set(plan['skipped_segment_indices']))
            or type(plan.get('skipped_ads')) is not int or plan['skipped_ads'] < 0
            or type(plan.get('skipped_ad_segments')) is not int or plan['skipped_ad_segments'] != len(plan['skipped_segment_indices'])
            or type(plan.get('skipped_ad_seconds')) not in (int, float) or not math.isfinite(plan['skipped_ad_seconds']) or plan['skipped_ad_seconds'] < 0
            or not isinstance(plan.get('cached_segments'), list)):
        raise FilterError('Invalid committed ad plan')
    if plan_identity(plan) != plan['identity']:
        raise FilterError('Ad plan identity mismatch')
    if catalog_sha256 is not None and plan['catalog_sha256'] != catalog_sha256:
        raise FilterError('Ad catalog changed during this download')
    if original is not None:
        if plan['snapshot_identity'] != original['identity']:
            raise FilterError('HLS snapshot changed during this download')
        try:
            raw_lines, regions, segments = parse_manifest(original['manifest'], original['url'])
        except Unknown:
            if plan['skipped_segment_indices'] or plan['manifest'] != original['manifest'] or plan['skipped_ads'] or plan['skipped_ad_seconds']:
                raise FilterError('Unsupported original cannot have ad omissions') from None
        else:
            skipped = set(plan['skipped_segment_indices'])
            complete = [r for r in regions if r['segments'] and {s['index'] for s in r['segments']} <= skipped]
            if (skipped - {s['index'] for r in complete for s in r['segments']} or len(skipped) >= len(segments)
                    or filtered_manifest(raw_lines, segments, skipped) != plan['manifest']
                    or len(complete) != plan['skipped_ads']
                    or abs(sum(r['duration_seconds'] for r in complete) - plan['skipped_ad_seconds']) > 1e-6):
                raise FilterError('Ad plan omissions do not match complete source regions')
    return plan


class SafeRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if not http_url(newurl):
            raise Unknown('unsafe_redirect')
        return super().redirect_request(req, fp, code, msg, headers, newurl)


class SegmentCache:
    def __init__(self, root, snapshot_identity, headers, rate_limit=None):
        self.root, self.headers = Path(root), headers
        if rate_limit is not None and (type(rate_limit) is not int or rate_limit <= 0):
            raise FilterError('Invalid ad scan download rate limit')
        self.rate_limit, self.rate_started = rate_limit, None
        self.directory = self.root / 'ad-cache'
        if self.directory.is_symlink():
            raise FilterError('Unsafe ad cache directory')
        self.directory.mkdir(exist_ok=True)
        self.index = self.directory / 'index.json'
        if self.index.is_symlink():
            raise FilterError('Unsafe ad cache index')
        self.snapshot_identity = snapshot_identity
        self.entries = {}
        self.network_bytes, self.reused_segments, self.reused_bytes = 0, 0, 0
        if self.index.exists():
            try:
                data = json.loads(self.index.read_text(encoding='utf-8'))
                if data.get('schema') != 1 or data.get('snapshot_identity') != snapshot_identity:
                    raise FilterError('Ad cache snapshot mismatch')
                for entry in data['segments']:
                    cache_path(self.root, entry)
                    if entry['url'] in self.entries:
                        raise FilterError('Duplicate ad cache URL')
                    if valid_cached(self.root, entry):
                        self.entries[entry['url']] = entry
                if sum(e['size'] for e in self.entries.values()) > MAX_TOTAL_BYTES:
                    raise FilterError('Ad cache exceeds byte budget')
            except (ValueError, KeyError, TypeError):
                raise FilterError('Invalid ad cache index') from None
        self.opener = build_opener(SafeRedirect())

    def _throttle(self):
        if self.rate_limit is None:
            return 0
        delay = self.network_bytes / self.rate_limit - (time.monotonic() - self.rate_started)
        if delay <= 0:
            return 0
        before = time.monotonic()
        # SIGTERM/SIGINT unwind this sleep normally, removing partial cache data.
        time.sleep(delay)
        return time.monotonic() - before

    def fetch(self, url, remaining):
        if url in self.entries:
            entry = self.entries[url]
            if entry['size'] > remaining:
                raise Unknown('candidate_byte_budget')
            self.reused_segments += 1
            self.reused_bytes += entry['size']
            return entry
        for attempt in range(2):
            try:
                return self._fetch_once(url, remaining)
            except TransientFetch:
                if attempt:
                    raise
            except HTTPError as exc:
                exc.close()
                if attempt or not 500 <= exc.code <= 599:
                    raise
            except URLError as exc:
                if attempt or not isinstance(exc.reason, (TimeoutError, ConnectionError)):
                    raise

    def _open_response(self, url):
        try:
            return self.opener.open(Request(url, headers=self.headers), timeout=10)
        except (TimeoutError, ConnectionError) as exc:
            raise TransientFetch('transport:' + type(exc).__name__) from None

    def _fetch_once(self, url, remaining):
        # Every failed transaction removes its partial file before retrying.
        # Discarded network bytes remain charged to both rate and global budgets.
        capacity = MAX_TOTAL_BYTES - sum(e['size'] for e in self.entries.values())
        allowance = min(remaining, MAX_TOTAL_BYTES - self.network_bytes, capacity)
        if allowance <= 0:
            raise Unknown('download_byte_budget')
        descriptor, temporary = tempfile.mkstemp(prefix='segment-', suffix='.part', dir=self.directory)
        size, hasher = 0, hashlib.sha256()
        deadline = time.monotonic() + 30
        if self.rate_limit is not None and self.rate_started is None:
            self.rate_started = time.monotonic()
        try:
            with os.fdopen(descriptor, 'wb') as target, self._open_response(url) as response:
                if not http_url(response.url):
                    raise Unknown('unsafe_response_url')
                if response.status != 200 or response.headers.get('Content-Range') is not None:
                    raise Unknown('requires_full_http_200_response')
                lengths = response.headers.get_all('Content-Length') or []
                if len(lengths) > 1 or lengths and not re.fullmatch(r'[0-9]+', lengths[0].strip()):
                    raise Unknown('invalid_content_length')
                expected = int(lengths[0].strip()) if lengths else None
                if expected is not None and expected > allowance:
                    raise Unknown('download_byte_budget')
                while True:
                    if size >= allowance:
                        raise Unknown('download_byte_budget')
                    try:
                        data = response.read(min(65536, allowance - size))
                    except IncompleteRead as exc:
                        self.network_bytes += len(exc.partial)
                        self._throttle()
                        raise TransientFetch('incomplete_http_body') from None
                    except (TimeoutError, ConnectionError) as exc:
                        raise TransientFetch('transport:' + type(exc).__name__) from None
                    self.network_bytes += len(data)
                    if not data:
                        break
                    size += len(data)
                    target.write(data)
                    hasher.update(data)
                    # Throttling is intentional waiting, not a stalled server.
                    deadline += self._throttle()
                    if time.monotonic() > deadline:
                        raise Unknown('download_time_budget')
                    if expected is not None and size == expected:
                        break
                if expected is not None and size != expected:
                    raise TransientFetch('content_length_mismatch')
                target.flush()
                os.fsync(target.fileno())
            if not size:
                raise Unknown('empty_segment')
            entry = {'url': url, 'path': 'ad-cache/' + digest(url.encode('utf-8')) + '.ts',
                     'size': size, 'sha256': hasher.hexdigest()}
            destination = cache_path(self.root, entry)
            os.replace(temporary, destination)
            self.entries[url] = entry
            atomic_json(self.index, {'schema': 1, 'snapshot_identity': self.snapshot_identity,
                                     'segments': list(self.entries.values())})
            return entry
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)


def candidate_fingerprint(region, entries, root, temporary, ffmpeg, ffprobe):
    local = temporary / 'candidate.m3u8'
    output = temporary / 'candidate.mp4'
    lines = ['#EXTM3U', '#EXT-X-VERSION:3', '#EXT-X-TARGETDURATION:%d' % math.ceil(max(s['duration_seconds'] for s in region['segments'])), '#EXT-X-MEDIA-SEQUENCE:0']
    for segment, entry in zip(region['segments'], entries):
        lines.extend(['#EXTINF:%s,' % segment['duration_seconds'], str(cache_path(root, entry).absolute())])
    lines.append('#EXT-X-ENDLIST')
    local.write_text('\n'.join(lines) + '\n', encoding='utf-8')
    data = run_json([ffprobe, '-v', 'error', '-protocol_whitelist', 'file', '-show_streams', '-of', 'json', str(local)])
    if sorted(s.get('codec_name', '') for s in data.get('streams', [])) != ['aac', 'h264']:
        raise Unknown('unsupported_candidate_streams')
    try:
        result = subprocess.run([ffmpeg, '-nostdin', '-v', 'warning', '-n', '-protocol_whitelist', 'file', '-i', str(local),
                                 '-map', '0', '-c', 'copy', str(output)], capture_output=True, text=True, timeout=120, check=True)
        if result.stderr.strip():
            raise Unknown('remux_warning')
    except (OSError, subprocess.SubprocessError) as exc:
        raise Unknown('remux_failed:' + type(exc).__name__) from None
    return fingerprint(output, ffprobe)


def classify(input_path, catalog_path, plan_path, ffmpeg, ffprobe):
    input_path, catalog_path, plan_path = map(Path, (input_path, catalog_path, plan_path))
    if any(not p.is_absolute() for p in (input_path, catalog_path, plan_path)) or not Path(ffmpeg).is_absolute() or not Path(ffprobe).is_absolute():
        raise FilterError('Classifier paths must be absolute')
    root = input_path.parent
    if root.name != '.resume' or root.is_symlink() or plan_path.parent != root or input_path.is_symlink() or plan_path.is_symlink():
        raise FilterError('Ad input and plan must share a real resume directory')
    try:
        original = json.loads(input_path.read_text(encoding='utf-8'))
        if (not isinstance(original, dict) or type(original.get('schema')) is not int or original['schema'] != 1
                or not HEX.fullmatch(str(original.get('identity', ''))) or not isinstance(original.get('manifest'), str)
                or not http_url(original.get('url')) or not isinstance(original.get('headers', {}), dict)):
            raise FilterError('Invalid ad input')
        rate_limit = original.get('rate_limit_bytes_per_second')
        if rate_limit is not None and (type(rate_limit) is not int or rate_limit <= 0):
            raise FilterError('Invalid ad scan download rate limit')
        headers = original.get('headers', {})
        if any(not isinstance(k, str) or not re.fullmatch(r"[!#$%&'*+.^_`|~0-9A-Za-z-]+", k)
               or not isinstance(v, str) or '\n' in v or '\r' in v for k, v in headers.items()):
            raise FilterError('Invalid ad request headers')
    except (ValueError, TypeError, AttributeError):
        raise FilterError('Invalid ad input') from None
    catalog, catalog_sha256 = read_catalog(catalog_path)
    if plan_path.exists():
        try:
            plan = validate_plan(json.loads(plan_path.read_text(encoding='utf-8')), original, catalog_sha256)
            try:
                _, _, retained = parse_manifest(plan['manifest'], original['url'])
            except Unknown:
                retained = []
            retained_urls = {s['url'] for s in retained}
            seen = set()
            for entry in plan['cached_segments']:
                cache_path(root, entry)
                if entry['url'] not in retained_urls or entry['url'] in seen:
                    raise FilterError('Unexpected ad plan cached URL')
                seen.add(entry['url'])
            if sum(e['size'] for e in plan['cached_segments']) > MAX_TOTAL_BYTES:
                raise FilterError('Ad plan cache budget exceeded')
            return plan
        except (ValueError, KeyError, TypeError, Unknown):
            raise FilterError('Cannot reuse committed ad plan') from None
    started = time.monotonic()
    print('HLS_AD_STAGE scan', flush=True)
    try:
        raw_lines, regions, segments = parse_manifest(original['manifest'], original['url'])
    except Unknown as exc:
        raw_lines, regions, segments = [], [], []
        unsupported = str(exc)
    else:
        unsupported = None
    cache = SegmentCache(root, original['identity'], headers, rate_limit=rate_limit)
    skipped, reports, eligible = set(), [], 0
    eligible_total = min(MAX_REGIONS, sum(bool(r['segments']) and r['duration_seconds'] <= MAX_SECONDS
                                        and len(r['segments']) <= MAX_SEGMENTS for r in regions))
    for region in regions:
        row = {key: region[key] for key in ('index', 'start_boundary', 'end_boundary', 'duration_seconds', 'marker_hint')}
        row.update(status='unknown', reason='not_scanned')
        reports.append(row)
        if not region['segments']:
            row['reason'] = 'empty_region'
        elif region['duration_seconds'] > MAX_SECONDS:
            row['reason'] = 'duration_budget'
        elif len(region['segments']) > MAX_SEGMENTS:
            row['reason'] = 'segment_budget'
        elif eligible >= MAX_REGIONS:
            row['reason'] = 'region_budget'
        else:
            eligible += 1
            try:
                entries, size = [], 0
                for segment in region['segments']:
                    entry = cache.fetch(segment['url'], MAX_BYTES - size)
                    entries.append(entry)
                    size += entry['size']
                print('HLS_AD_STAGE probe', flush=True)
                with tempfile.TemporaryDirectory(prefix='.ad-scan-', dir=root) as temp:
                    fp = candidate_fingerprint(region, entries, root, Path(temp), ffmpeg, ffprobe)
                matches = [s['label'] for s in catalog['signatures'] if same_content(fp, s['fingerprint'])]
                row.update(fingerprint=fp, matched_references=matches, reason='content_match' if matches else 'no_full_content_match')
                if matches:
                    row['status'] = 'confirmed'
                    skipped.update(s['index'] for s in region['segments'])
            except Exception as exc:
                row['reason'] = type(exc).__name__ + ':' + str(exc)
            print('HLS_AD_CANDIDATE %d %d' % (eligible, eligible_total), flush=True)
    if skipped and len(skipped) == len(segments):
        skipped.clear()
        for row in reports:
            if row['status'] == 'confirmed':
                row.update(status='unknown', reason='would_remove_all_media')
    manifest = filtered_manifest(raw_lines, segments, skipped) if segments else original['manifest']
    confirmed = [r for r in regions if r['segments'] and all(s['index'] in skipped for s in r['segments'])]
    retained_urls = {s['url'] for s in segments if s['index'] not in skipped}
    plan = {'schema': 1, 'policy': POLICY, 'catalog_sha256': catalog_sha256, 'snapshot_identity': original['identity'],
            'manifest': manifest, 'skipped_ads': len(confirmed), 'skipped_ad_segments': len(skipped),
            'skipped_ad_seconds': sum(r['duration_seconds'] for r in confirmed), 'skipped_segment_indices': sorted(skipped),
            'cached_segments': [entry for url, entry in cache.entries.items() if url in retained_urls],
            'regions': reports, 'unsupported_reason': unsupported,
            'cost': {'network_bytes': cache.network_bytes, 'cache_reuse_count': cache.reused_segments,
                     'cache_reuse_bytes': cache.reused_bytes, 'elapsed_seconds': time.monotonic() - started}}
    plan['identity'] = plan_identity(plan)
    validate_plan(plan, original, catalog_sha256)
    try:
        atomic_json(plan_path, plan, replace=False)
    except FileExistsError:
        # An existing frozen decision must win; never change its skip set under
        # a possible checkpoint. The caller serializes writers for this job.
        return classify(input_path, catalog_path, plan_path, ffmpeg, ffprobe)
    return plan


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    for name in ('input', 'catalog', 'plan', 'ffmpeg', 'ffprobe'):
        parser.add_argument('--' + name, required=True)
    args = parser.parse_args(argv)
    # Let normal unwinding remove our run's temporary remux files on cancellation.
    signal.signal(signal.SIGTERM, lambda *_: sys_exit_cancelled())
    classify(args.input, args.catalog, args.plan, args.ffmpeg, args.ffprobe)


def sys_exit_cancelled():
    raise SystemExit(130)


if __name__ == '__main__':
    try:
        main()
    except (FilterError, OSError) as exc:
        import sys
        print('ERROR: media-dler HLS ads: ' + str(exc), file=sys.stderr, flush=True)
        sys.exit(1)
