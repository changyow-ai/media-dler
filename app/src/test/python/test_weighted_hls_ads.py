"""Weighted runtime contracts, using synthetic features and disposable local media."""
import copy
import importlib.util
import io
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
ASSETS = Path(__file__).resolve().parents[2] / 'main/assets'
SCRIPTS = ASSETS.parents[3] / 'scripts'
sys.path.insert(0, str(ASSETS))
import hls_ad_filter as ads
import weighted_hls_ads as runtime
import hls_media_validation as download_validation


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


probe = module('weighted_probe_test', SCRIPTS / 'probe-hls-ad-features.py')
scorer = module('weighted_score_test', SCRIPTS / 'score-hls-ad-features.py')


def manifest(durations):
    lines = ['#EXTM3U', '#EXT-X-TARGETDURATION:90']
    for index, duration in enumerate(durations):
        if index:
            lines.append('#EXT-X-DISCONTINUITY')
        lines += ['#EXTINF:%s,' % duration, 'part%d.ts' % index]
    return '\n'.join(lines + ['#EXT-X-ENDLIST']) + '\n'


def features(commercial=False, valid=True):
    return {'candidate_gate': 'valid' if valid else 'unknown',
        'inputs': {'candidate': {'status': 'ok' if valid else 'unknown'}}, 'errors': [],
        'ocr': {'candidate': {'samples': [{'time_seconds': t, 'observations': [
            {'text': 'example.com register', 'confidence': 1, 'box': [.2, .3, .6, .2]}] if commercial else []}
            for t in (1, 2, 3)]}}}


def loaded_media(codecs=('h264', 'aac')):
    return {'public': {'status': 'ok', 'metadata': {
        'streams': [{'codec_name': codec} for codec in codecs]}}}


class WallTimeProfileTests(unittest.TestCase):
    def test_exclusive_nested_exception_timing_and_counts_reconcile(self):
        now = [0.0]
        profile = runtime.WallTimeProfile(lambda: now[0])
        now[0] += 1
        with self.assertRaisesRegex(ValueError, 'failure'):
            with profile.measure('remux'):
                now[0] += 2
                with profile.measure('cache_fetch'):
                    now[0] += 3
                now[0] += 1
                with profile.measure('remux'):
                    now[0] += 4
                    raise ValueError('failure')
        now[0] += 2
        timing = profile.snapshot()
        self.assertEqual(timing['total_seconds'], 13)
        self.assertEqual(timing['categories']['remux'], {'seconds': 7, 'calls': 1})
        self.assertEqual(timing['categories']['cache_fetch'], {'seconds': 3, 'calls': 1})
        self.assertEqual(timing['categories']['unattributed']['seconds'], 3)
        self.assertEqual(sum(v['seconds'] for v in timing['categories'].values()), timing['total_seconds'])
        self.assertEqual(profile.stack, [])

    def test_native_categories_and_patched_functions_restore_after_exception(self):
        now = [0.0]
        profile = runtime.WallTimeProfile(lambda: now[0])
        def native(command):
            now[0] += 2
        def frame(*args):
            fake_probe.run(['ffmpeg', '-vcodec', 'png'])
        fake_probe = SimpleNamespace(run=native,
            **{name: frame if name.startswith('extract_png') else lambda *args: None
               for name in ('load_slice', 'visual_summary', 'audio_summary', 'bridge_evidence',
                            'extract_png', 'extract_png_batch')})
        fake_scorer = SimpleNamespace(score_case=lambda *_: None)
        cache = SimpleNamespace(fetch=lambda *_: None)
        original_probe = vars(fake_probe).copy()
        original_score, original_fetch = fake_scorer.score_case, cache.fetch
        original_remux, original_atomic = runtime.FeatureScanner.remux, ads.atomic_json
        with self.assertRaisesRegex(RuntimeError, 'stop'):
            with profile.instrument(fake_probe, fake_scorer, cache):
                fake_probe.run(['ffprobe', '-show_streams'])
                fake_probe.run(['ffmpeg', '-c', 'copy'])
                fake_probe.run(['ffmpeg', '-f', 'rawvideo'])
                fake_probe.run(['ffmpeg', '-f', 's16le'])
                fake_probe.extract_png_batch(None)
                raise RuntimeError('stop')
        self.assertEqual(vars(fake_probe), original_probe)
        self.assertIs(fake_scorer.score_case, original_score)
        self.assertIs(cache.fetch, original_fetch)
        self.assertIs(runtime.FeatureScanner.remux, original_remux)
        self.assertIs(ads.atomic_json, original_atomic)
        timing = profile.snapshot()
        self.assertEqual(timing['total_seconds'], 10)
        self.assertEqual(timing['categories']['ffprobe_metadata'], {'seconds': 2, 'calls': 1})
        self.assertEqual(timing['categories']['remux'], {'seconds': 2, 'calls': 1})
        self.assertEqual(timing['categories']['full_av_decode'], {'seconds': 4, 'calls': 2})
        self.assertEqual(timing['categories']['frame_extraction'], {'seconds': 2, 'calls': 1})


class OCRBoundTests(unittest.TestCase):
    def setUp(self):
        self.features = features()

    def bound(self, av):
        with patch.object(scorer, 'scene_score', return_value={'points': av}), \
             patch.object(scorer, 'audio_score', return_value={'points': 0}):
            return runtime.candidate_ocr_bound(self.features, scorer)

    def test_strict_threshold_and_numeric_margin_preserve_borderline_candidates(self):
        bound = self.bound(4)
        self.assertAlmostEqual(bound['remaining_content_upper_bound'], 50)
        self.assertAlmostEqual(bound['score_upper_bound'], 54, places=6)
        self.assertEqual(bound['candidate_frames_max'], 2)
        self.assertFalse(bound['score_is_exact'])
        self.assertIsNotNone(self.bound(5 - 1e-6))
        self.assertIsNone(self.bound(5))
        self.assertIsNone(self.bound(5 - 1e-10))
        self.assertIsNone(self.bound(40))

    def test_one_frame_profile_retains_its_original_bound(self):
        with patch.dict(runtime.OCR_SAMPLING_PROFILE, candidate_fractions=[.1]):
            bound = self.bound(29)
            self.assertAlmostEqual(bound['remaining_content_upper_bound'], 25)
            self.assertAlmostEqual(bound['score_upper_bound'], 54, places=6)
            self.assertEqual(bound['candidate_frames_max'], 1)
            self.assertIsNone(self.bound(30))
            self.assertIsNone(self.bound(30 - 1e-10))

    def test_bound_is_derived_from_sampling_and_frozen_settings(self):
        with patch.dict(scorer.SETTINGS, ocr_expected_frames=1):
            self.assertIsNone(self.bound(0))  # Remaining C may reach its cap of 60.
        with patch.dict(runtime.OCR_SAMPLING_PROFILE, candidate_fractions=[.1, .5, .9]):
            self.assertIsNone(self.bound(0))
        with patch.dict(scorer.SETTINGS, threshold=20):
            self.assertIsNone(self.bound(0))
        with patch.dict(scorer.SETTINGS, programme_negative_max=-1):
            self.assertIsNone(self.bound(0))

    def test_invalid_sources_or_existing_errors_cannot_use_bound(self):
        self.features['candidate_gate'] = 'unknown'
        self.assertIsNone(self.bound(0))
        self.features['candidate_gate'] = 'valid'
        self.features['inputs']['candidate']['status'] = 'unknown'
        self.assertIsNone(self.bound(0))
        self.features['inputs']['candidate']['status'] = 'ok'
        self.features['errors'].append('before:ocr:provider_failed')
        self.assertIsNone(self.bound(0))

    def test_maximum_candidate_text_and_negative_cues_stay_within_bound(self):
        for fractions, av in (([.1], 29), ([.05, .1], 4)):
            candidate = features(commercial=True)
            candidate['ocr']['candidate']['samples'] = candidate['ocr']['candidate']['samples'][:len(fractions)]
            for sample in candidate['ocr']['candidate']['samples']:
                sample['observations'][0]['box'] = [0, 0, 1, 1]
            with self.subTest(fractions=fractions), \
                 patch.dict(runtime.OCR_SAMPLING_PROFILE, candidate_fractions=fractions), \
                 patch.object(scorer, 'scene_score', return_value={'points': av}), \
                 patch.object(scorer, 'audio_score', return_value={'points': 0}):
                bound = runtime.candidate_ocr_bound(candidate, scorer)
                complete = scorer.score_case(candidate)
                self.assertLessEqual(complete['score'], bound['score_upper_bound'])
                self.assertEqual(complete['decision'], 'unknown')
                for sample in candidate['ocr']['candidate']['samples']:
                    sample['observations'].append(
                        {'text': '演員 配音 字幕製作', 'confidence': 1, 'box': [.1, .1, .8, .2]})
                penalized = scorer.score_case(candidate)
                self.assertLessEqual(penalized['score'], bound['score_upper_bound'])
                self.assertEqual(penalized['decision'], 'unknown')


class RuntimeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve() / '.resume'
        self.root.mkdir()
        self.original = {'schema': 1, 'identity': 'a' * 64, 'url': 'https://example.test/list.m3u8',
                         'manifest': manifest([70, 10, 10, 70, 10, 10, 70]),
                         'headers': {}, 'known_catalog_enabled': False}
        self.input = self.root / 'input.json'
        self.input.write_text(json.dumps(self.original))
        self.plan = self.root / 'ad-plan.json'
        self.policy = self.root / 'policy.json'
        self.policy.write_text('{}')
        self.catalog = ASSETS / 'hls_ad_fingerprints.json'

    def run_scan(self, fake=None):
        with patch.object(runtime, 'load_policy', return_value=({}, 'b' * 64)), \
             patch.object(runtime, 'feature_modules', return_value=(probe, scorer)), \
             patch.object(runtime.FeatureScanner, 'process', side_effect=fake or (lambda group: features())):
            return runtime.classify(self.input, self.catalog, self.policy, self.plan, '/ffmpeg', '/ffprobe')

    def test_all_adjacent_ranges_without_four_region_limit(self):
        _, regions, _ = ads.parse_manifest(manifest([10] * 8), self.original['url'])
        groups = list(runtime.candidates(regions))
        self.assertTrue(any(len(g['region_indices']) == 6 for g in groups))
        self.assertTrue(all(g['duration_seconds'] <= 60 for g in groups))
        self.assertEqual(len(groups), sum(min(6, 8 - i) for i in range(8)))

    def test_population_hash_matches_canonical_order_and_keeps_only_compact_prefix(self):
        _, regions, _ = ads.parse_manifest(manifest([1] * 8), self.original['url'])
        compact = list(runtime.candidates(regions, media_limit=0))
        full = list(runtime.candidates(regions))
        with patch.object(runtime, 'MAX_CANDIDATES', 3):
            prefix, count, population_sha = runtime.candidate_population(regions)
        self.assertEqual(prefix, compact[:3])
        self.assertEqual(count, len(full))
        self.assertEqual(population_sha, ads.digest(ads.canonical(compact)))
        self.assertTrue(all(set(value) == {'region_indices', 'segment_indices', 'duration_seconds'}
                            for value in compact))
        self.assertEqual(compact, [{key: group[key] for key in compact[0]} for group in full])
        limited = list(runtime.candidates(regions, media_limit=3))
        self.assertEqual(limited[:3], full[:3])
        self.assertEqual(limited[3:], compact[3:])

    def test_population_over_old_65536_limit_retains_only_4096_compact_candidates(self):
        # 1123 one-second regions have 65610 eligible complete ranges. Opaque
        # media evidence demonstrates that collection never serializes/copies it.
        opaque = object()
        regions = [{'duration_seconds': 1, 'segments': [{'index': index, 'opaque': opaque}],
                    'start_boundary': None, 'end_boundary': None} for index in range(1123)]
        prefix, count, population_sha = runtime.candidate_population(regions)
        self.assertEqual(count, 65610)
        self.assertEqual(len(prefix), 4096)
        self.assertTrue(all(set(value) == {'region_indices', 'segment_indices', 'duration_seconds'}
                            for value in prefix))
        journal = runtime.CandidateJournal(self.root, self.original, 'b' * 64, prefix,
            population_count=count, population_sha256=population_sha)
        self.assertEqual(journal.binding['candidate_count'], 65610)
        self.assertEqual(journal.binding['reusable_prefix_count'], 4096)
        self.assertEqual(journal.binding['candidates_sha256'], population_sha)

    def test_timing_matches_analysis_total_and_keeps_original_selection(self):
        original_ocr = probe.ocr_png
        original_run, original_score = probe.run, scorer.score_case
        plan = self.run_scan(lambda group: features(commercial=group['duration_seconds'] == 20))
        timing = plan['cost']['timing']
        self.assertEqual(plan['skipped_ads'], 2)
        self.assertEqual(plan['skipped_ad_segments'], 4)
        self.assertEqual(timing['total_seconds'], plan['cost']['elapsed_seconds'])
        self.assertAlmostEqual(sum(v['seconds'] for v in timing['categories'].values()), timing['total_seconds'])
        self.assertEqual(timing['weighted_only_seconds'], timing['total_seconds'])
        self.assertEqual(timing['categories']['pure_scoring']['calls'], 6)
        self.assertEqual(timing['categories']['plan_checkpoint_io']['calls'], 14)
        self.assertEqual(timing['reuse']['candidates_scanned'], 6)
        self.assertEqual(json.loads(self.plan.read_text())['cost']['timing'], timing)
        self.assertIs(probe.run, original_run)
        self.assertIs(probe.ocr_png, original_ocr)
        self.assertIs(scorer.score_case, original_score)

    def test_known_catalog_current_wait_is_separate_from_saved_cost_and_weighted_work(self):
        self.original['known_catalog_enabled'] = True
        self.input.write_text(json.dumps(self.original))
        now = [0.0]
        def known(*args):
            now[0] += 4
            return {'cost': {'elapsed_seconds': 3}, 'skipped_segment_indices': []}
        def weighted(group):
            now[0] += 2
            return features()
        with patch.object(runtime.time, 'monotonic', side_effect=lambda: now[0]), \
             patch.object(ads, 'classify', side_effect=known):
            plan = self.run_scan(weighted)
        timing = plan['cost']['timing']
        self.assertEqual(timing['total_seconds'], 16)
        self.assertEqual(timing['known_catalog_seconds'], 4)
        self.assertEqual(timing['known_catalog_reported_elapsed_seconds'], 3)
        self.assertEqual(timing['weighted_only_seconds'], 12)

    def test_interrupted_candidate_resume_reuses_prefix_before_first_progress(self):
        self.original['known_catalog_enabled'] = True
        self.input.write_text(json.dumps(self.original))
        _, regions, _ = ads.parse_manifest(self.original['manifest'], self.original['url'])
        ordered = list(runtime.candidates(regions))
        phase, now = ['baseline'], [0.0]
        calls = {name: [] for name in ('decode', 'ocr', 'score')}
        original_score, original_atomic = scorer.score_case, ads.atomic_json
        original_report = ads._report_progress

        def operation(name, index, seconds):
            if phase[0] == 'resume' and index < 2:
                self.fail('completed candidate repeated %s: %d' % (name, index))
            calls[name].append(index)
            now[0] += seconds

        def process(group):
            index = ordered.index(group)
            operation('decode', index, 2)
            if phase[0] == 'interrupted' and index == 2:
                raise KeyboardInterrupt('interrupted inside candidate K+1')
            operation('ocr', index, 1)
            value = features(commercial=group['duration_seconds'] == 20, valid=index != 0)
            value['id'] = str(index)
            return value

        def score(value):
            operation('score', int(value['id']), 1)
            return original_score(value)

        def atomic(*args, **kwargs):
            result = original_atomic(*args, **kwargs)
            now[0] += .25
            return result

        def known(*args):
            now[0] += 4
            # A nested legacy population must not reset restored K/unknown/time.
            ads._report_progress('source', [], 0, 0)
            return {'cost': {'elapsed_seconds': 90}, 'skipped_segment_indices': []}

        with patch.object(runtime.time, 'monotonic', side_effect=lambda: now[0]), \
             patch.object(scorer, 'score_case', side_effect=score), \
             patch.object(ads, 'atomic_json', side_effect=atomic), \
             patch.object(ads, 'classify', side_effect=known), patch('sys.stdout', io.StringIO()):
            baseline = self.run_scan(process)
            baseline_audits = json.loads((self.root / 'weighted-features.json').read_text())
            self.plan.unlink()
            shutil.rmtree(self.root / 'analysis-checkpoint')
            phase[0], now[0] = 'interrupted', 0
            for values in calls.values():
                values.clear()
            with self.assertRaises(KeyboardInterrupt):
                self.run_scan(process)
            self.assertFalse(self.plan.exists())
            self.assertIs(ads._report_progress, original_report)
            records = sorted((self.root / 'analysis-checkpoint').glob('*/[0-9]*.json'))
            self.assertEqual(len(records), 2)
            committed = [path.read_bytes() for path in records]
            saved = json.loads(committed[-1])['payload']['cumulative_elapsed_seconds']
            self.assertGreaterEqual(saved, 12)  # known scan + two complete candidates + IO
            phase[0], now[0] = 'resume', 100
            for values in calls.values():
                values.clear()
            output = io.StringIO()
            with patch('sys.stdout', output):
                resumed = self.run_scan(process)
        events = [json.loads(line.removeprefix('HLS_AD_PROGRESS '))
                  for line in output.getvalue().splitlines() if line.startswith('HLS_AD_PROGRESS ')]
        self.assertEqual(events[0]['stage'], 'restoring')
        first_source = next(event for event in events if event['stage'] == 'source')
        for event in (events[0], first_source):
            self.assertEqual(event['completed'], 2)
            self.assertEqual(event['unknown'], 1)
            self.assertEqual(event['total'], 6)
            self.assertGreaterEqual(event['elapsed_seconds'], saved)
        self.assertEqual(calls, {name: [2, 3, 4, 5] for name in calls})
        self.assertEqual([path.read_bytes() for path in records], committed)
        self.assertEqual(resumed['regions'], baseline['regions'])
        self.assertEqual(resumed['groups'], baseline['groups'])
        self.assertEqual(json.loads((self.root / 'weighted-features.json').read_text()), baseline_audits)
        cost = resumed['cost']
        self.assertEqual(cost['checkpoint']['reused_candidates'], 2)
        self.assertEqual(cost['checkpoint']['new_candidates'], 4)
        self.assertEqual(cost['checkpoint']['saved_elapsed_seconds'], saved)
        self.assertEqual(cost['current_attempt_elapsed_seconds'], cost['timing']['total_seconds'])
        self.assertEqual(cost['elapsed_seconds'], saved + cost['current_attempt_elapsed_seconds'])
        self.assertEqual(cost['cumulative_elapsed_seconds'], cost['elapsed_seconds'])
        self.assertEqual(cost['timing']['categories']['pure_scoring']['calls'], 4)
        self.assertEqual(cost['timing']['reuse']['candidates_scanned'], 4)
        self.assertEqual(cost['timing']['known_catalog_seconds'], 4)
        self.assertEqual(cost['timing']['known_catalog_reported_elapsed_seconds'], 90)
        self.assertIs(ads._report_progress, original_report)

    def test_cancelled_classification_restores_ocr_and_timing_wrappers(self):
        original_ocr, original_run = probe.ocr_png, probe.run
        original_score, original_atomic = scorer.score_case, ads.atomic_json
        original_remux = runtime.FeatureScanner.remux
        def cancel(group):
            raise KeyboardInterrupt()
        with self.assertRaises(KeyboardInterrupt):
            self.run_scan(cancel)
        self.assertIs(probe.ocr_png, original_ocr)
        self.assertIs(probe.run, original_run)
        self.assertIs(scorer.score_case, original_score)
        self.assertIs(ads.atomic_json, original_atomic)
        self.assertIs(runtime.FeatureScanner.remux, original_remux)
        self.assertFalse(self.plan.exists())
        self.assertFalse(list(self.root.glob('.weighted-*')))

    def test_progress_counts_unknown_and_budgeted_candidates_as_processed(self):
        output = io.StringIO()
        with patch('sys.stdout', output), patch.object(runtime, 'MAX_CANDIDATES', 1):
            plan = self.run_scan(lambda group: features(valid=False))
        events = [json.loads(line.removeprefix('HLS_AD_PROGRESS '))
                  for line in output.getvalue().splitlines() if line.startswith('HLS_AD_PROGRESS ')]
        final = events[-1]
        self.assertEqual(final['stage'], 'complete')
        self.assertEqual(final['completed'], plan['coverage']['eligible_candidates'])
        self.assertEqual(final['total'], final['completed'])
        self.assertEqual(final['unknown'], final['completed'])
        self.assertEqual(final['ads'], 0)
        self.assertGreaterEqual(final['elapsed_seconds'], 0)

    def test_resume_regenerates_budget_unknown_rows_without_scanner_or_scorer(self):
        with patch.object(runtime, 'MAX_CANDIDATES', 1), patch('sys.stdout', io.StringIO()):
            first = self.run_scan(lambda _: features(valid=False))
            header = next((self.root / 'analysis-checkpoint').glob('*/header.json'))
            binding = json.loads(header.read_text())
            self.assertEqual(binding['candidate_count'], 6)
            self.assertEqual(binding['reusable_prefix_count'], 1)
            self.plan.unlink()
            with patch.object(scorer, 'score_case', side_effect=AssertionError('completed score repeated')):
                resumed = self.run_scan(lambda _: self.fail('completed scanner repeated'))
        self.assertEqual(resumed['regions'], first['regions'])
        self.assertEqual(resumed['coverage'], first['coverage'])
        self.assertEqual(resumed['coverage']['candidates_scanned'], 1)
        self.assertEqual(resumed['coverage']['unscanned_candidates'], 5)
        self.assertEqual(resumed['cost']['checkpoint']['reused_candidates'], 1)
        self.assertEqual(resumed['cost']['checkpoint']['new_candidates'], 0)
        self.assertEqual(resumed['cost']['timing']['categories']['pure_scoring']['calls'], 0)

    def test_failed_atomic_candidate_commit_is_not_reported_complete_or_published(self):
        real_commit = runtime.CandidateJournal.commit
        def commit(journal, ordinal, *args):
            if ordinal == 1:
                raise OSError('no space left on device')
            return real_commit(journal, ordinal, *args)
        output = io.StringIO()
        with patch.object(runtime.CandidateJournal, 'commit', new=commit), patch('sys.stdout', output):
            with self.assertRaisesRegex(OSError, 'no space'):
                self.run_scan()
        self.assertFalse(self.plan.exists())
        events = [json.loads(line.removeprefix('HLS_AD_PROGRESS '))
                  for line in output.getvalue().splitlines() if line.startswith('HLS_AD_PROGRESS ')]
        self.assertEqual(max(event['completed'] for event in events), 1)
        self.assertFalse(any(event['stage'] == 'complete' for event in events))
        with patch('sys.stdout', io.StringIO()):
            resumed = self.run_scan()
        self.assertEqual(resumed['cost']['checkpoint']['reused_candidates'], 1)
        self.assertEqual(resumed['cost']['checkpoint']['new_candidates'], 5)

    def test_restored_progress_includes_counters_and_saved_elapsed(self):
        plan = self.run_scan()
        output = io.StringIO()
        with patch('sys.stdout', output), patch.object(runtime, 'load_policy', return_value=({}, 'b' * 64)):
            runtime.classify(self.input, self.catalog, self.policy, self.plan, '/ffmpeg', '/ffprobe')
        events = [json.loads(line.removeprefix('HLS_AD_PROGRESS ')) for line in output.getvalue().splitlines()]
        self.assertEqual([e['stage'] for e in events], ['restoring', 'complete'])
        self.assertEqual(events[0]['completed'], plan['coverage']['eligible_candidates'])
        self.assertGreaterEqual(events[0]['elapsed_seconds'], plan['cost']['elapsed_seconds'])

    def test_progress_snapshot_retains_restored_counters_during_ocr(self):
        output = io.StringIO()
        with patch.object(runtime.time, 'monotonic', side_effect=[100, 103]), patch('sys.stdout', output):
            progress = runtime.AnalysisProgress(completed=4, total=12, unknown=2, elapsed_seconds=90)
            progress.emit('ocr')
        event = json.loads(output.getvalue().removeprefix('HLS_AD_PROGRESS '))
        self.assertEqual(event, {'stage': 'ocr', 'completed': 4, 'total': 12, 'unknown': 2,
                                 'ads': None, 'elapsed_seconds': 93})

    def test_automatic_groups_count_ads_not_regions_and_frozen_early_reuse(self):
        # No region IDs are supplied to the planner. Each synthetic complete
        # commercial has two 10-second parts; individual halves lack evidence.
        plan = self.run_scan(lambda group: features(commercial=group['duration_seconds'] == 20))
        self.assertEqual(plan['skipped_ads'], 2)
        self.assertEqual(len(plan['groups']), 2)
        self.assertEqual(plan['skipped_ad_segments'], 4)
        self.assertEqual(plan['skipped_ad_seconds'], 40)
        self.assertEqual(self.input.read_text(), json.dumps(self.original))
        latest = json.loads((self.root / 'weighted-latest.json').read_text())
        self.assertEqual(latest['completed_index'], plan['coverage']['candidates_scanned'])
        self.assertEqual(latest['total'], plan['coverage']['candidates_scanned'])
        self.assertEqual(latest['snapshot_identity'], self.original['identity'])
        self.assertEqual(latest['catalog_sha256'], plan['catalog_sha256'])
        self.assertEqual(latest['weighted']['score'], scorer.score_case(latest['features'])['score'])
        before = self.plan.read_bytes()
        with patch.object(runtime, 'load_policy', return_value=({}, 'b' * 64)), \
             patch.object(runtime, 'feature_modules', side_effect=AssertionError('frozen before feature loading')), \
             patch.object(ads.SegmentCache, '__init__', side_effect=AssertionError('frozen before network')):
            self.assertEqual(runtime.classify(self.input, self.catalog, self.policy, self.plan,
                             '/ffmpeg', '/ffprobe'), plan)
        self.assertEqual(self.plan.read_bytes(), before)

    def test_group_tampering_identity_and_source_partition_rejected(self):
        plan = self.run_scan(lambda group: features(commercial=group['duration_seconds'] == 20))
        for mutate in (lambda p: p['groups'][0]['region_indices'].pop(),
                       lambda p: p.update(skipped_ads=4),
                       lambda p: p['groups'][0].update(duration_seconds=19)):
            modified = copy.deepcopy(plan)
            mutate(modified)
            with self.assertRaises(ads.FilterError):
                ads.validate_plan(modified, self.original, 'b' * 64)
        modified = copy.deepcopy(plan)
        modified['groups'][0]['region_indices'] = [0, 1]
        modified['identity'] = ads.plan_identity(modified)
        with self.assertRaises(ads.FilterError):
            ads.validate_plan(modified, self.original, 'b' * 64)

    def test_no_ads_and_invalid_candidate_decode_preserve_exact_manifest(self):
        for fake in (lambda _: features(), lambda _: features(commercial=True, valid=False)):
            plan = self.run_scan(fake)
            self.assertEqual(plan['manifest'], self.original['manifest'])
            self.assertEqual(plan['groups'], [])
            self.plan.unlink()
            shutil.rmtree(self.root / 'analysis-checkpoint')

    def test_budget_and_unsupported_are_explicit_unknown(self):
        with patch.object(runtime, 'MAX_CANDIDATES', 1):
            plan = self.run_scan()
        self.assertFalse(plan['coverage']['complete'])
        self.assertGreater(plan['coverage']['unscanned_candidates'], 0)
        self.assertFalse(plan['coverage']['candidate_ocr_evidence_complete'])
        self.assertTrue(any('candidate_count_budget' in row['errors'] for row in plan['regions']))
        self.plan.unlink()
        self.original['manifest'] = self.original['manifest'].replace('#EXTM3U', '#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI="x"')
        self.input.write_text(json.dumps(self.original))
        plan = self.run_scan()
        self.assertEqual(plan['skipped_ads'], 0)
        self.assertEqual(plan['unsupported_reason'], 'unsupported_manifest_tag')
        self.assertFalse(plan['coverage']['complete'])
        self.assertFalse(plan['coverage']['candidate_ocr_evidence_complete'])

    def test_dense_playlist_plan_fits_android_limit_and_reuses_full_progress(self):
        self.original['manifest'] = manifest([1] * 200)
        self.input.write_text(json.dumps(self.original))
        output = io.StringIO()
        with patch.object(runtime, 'MAX_CANDIDATES', 1), patch('sys.stdout', output):
            plan = self.run_scan(lambda _: features(commercial=True))
            self.assertLessEqual(self.plan.stat().st_size, 4 * 1024 * 1024)
            self.assertEqual(plan['skipped_segment_indices'], [0])
            self.assertEqual(plan['skipped_ads'], 1)
            self.assertEqual(plan['coverage']['eligible_candidates'], 10230)
            self.assertEqual(plan['coverage']['unscanned_candidates'], 10229)
            self.assertFalse(plan['coverage']['complete'])
            self.assertLess(len(plan['regions']), 10230)
            self.assertEqual(self.run_scan(lambda _: self.fail('frozen plan rescanned')), plan)
        final = json.loads(output.getvalue().split('HLS_AD_PROGRESS ')[-1])
        self.assertEqual((final['completed'], final['total'], final['unknown']), (10230, 10230, 10229))

    def test_omission_source_gate_requires_final_validator_codecs(self):
        _, regions, _ = ads.parse_manifest(manifest([5]), self.original['url'])
        scanner = runtime.FeatureScanner(self.root, self.root,
            ads.SegmentCache(self.root, 'a' * 64, {}), regions, '/ffmpeg', '/ffprobe', probe)
        for codecs in [('h264', 'aac'), ('hevc', 'aac'), ('mpeg2video', 'aac'), ('h264', 'mp3'), ('h264',)]:
            with self.subTest(codecs=codecs), \
                 patch.object(scanner, 'remux', return_value=self.root / 'candidate.ts'), \
                 patch.object(probe, 'load_slice', return_value=loaded_media(codecs)), \
                 patch.object(probe, 'extract_ocr', return_value={'status': 'ok', 'samples': []}), \
                 patch.object(probe, 'bridge_evidence', return_value={'status': 'unknown'}):
                result = scanner.process(next(runtime.candidates(regions)))
                supported = codecs == ('h264', 'aac')
                self.assertEqual(result['candidate_gate'], 'valid' if supported else 'unknown')
                if not supported:
                    self.assertIn('candidate:requires_requested_streams:aac,h264', result['errors'])
                    plan = self.run_scan(lambda _: result)
                    self.assertEqual(plan['manifest'], self.original['manifest'])
                    self.assertEqual(plan['skipped_ads'], 0)
                    self.plan.unlink()
                    shutil.rmtree(self.root / 'analysis-checkpoint')

    def test_playlist_edge_absence_is_expected_but_present_failure_is_unknown(self):
        _, regions, _ = ads.parse_manifest(manifest([5]), self.original['url'])
        cache = ads.SegmentCache(self.root, 'a' * 64, {})
        scanner = runtime.FeatureScanner(self.root, self.root, cache, regions, '/ffmpeg', '/ffprobe', probe)
        loaded = loaded_media()
        def candidate_ocr(media, role, *_, **kwargs):
            return {'status': 'ok', 'samples': []}
        with patch.object(scanner, 'remux', return_value=self.root / 'candidate.ts'), \
             patch.object(probe, 'load_slice', return_value=loaded), \
             patch.object(probe, 'extract_ocr', side_effect=candidate_ocr):
            result = scanner.process(next(runtime.candidates(regions)))
        self.assertEqual(result['expected_absences'], ['before', 'after'])
        self.assertEqual(result['errors'], [])
        self.assertEqual(result['candidate_gate'], 'valid')
        self.assertEqual(result['inputs']['before'], {'status': 'absent', 'reason': 'playlist_edge'})
        self.original['manifest'] = manifest([5])
        self.input.write_text(json.dumps(self.original))
        plan = self.run_scan(lambda _: result)
        self.assertEqual(plan['coverage']['ocr_failure_count'], 0)
        self.assertEqual(plan['coverage']['expected_absence_count'], 2)
        self.assertTrue(plan['coverage']['analysis_complete'])
        self.assertTrue(plan['coverage']['complete'])
        self.plan.unlink()
        shutil.rmtree(self.root / 'analysis-checkpoint')
        with patch.object(scanner, 'remux', return_value=self.root / 'candidate.ts'), \
             patch.object(probe, 'load_slice', return_value=loaded), \
             patch.object(probe, 'extract_ocr', side_effect=candidate_ocr), \
             patch.object(scanner, 'outside', return_value=(None, {'status': 'unknown', 'reason': 'decode_failed', 'samples': []})):
            failed = scanner.process(next(runtime.candidates(regions)))
        self.assertEqual(failed['expected_absences'], [])
        self.assertEqual(failed['inputs']['before']['status'], 'unknown')
        self.assertIn('before:ocr:decode_failed', failed['errors'])
        plan = self.run_scan(lambda _: failed)
        self.assertGreater(plan['coverage']['ocr_failure_count'], 0)
        self.assertEqual(plan['coverage']['expected_absence_count'], 0)
        self.assertFalse(plan['coverage']['analysis_complete'])

    def test_runtime_uses_two_opening_candidates_and_midpoint_context_without_provider_failure(self):
        expected_profile = {'name': 'front-two-960-v1', 'candidate_fractions': [.05, .1],
                            'context_fractions': [.5], 'max_width': 960}
        self.assertEqual(runtime.OCR_SAMPLING_PROFILE, expected_profile)
        _, regions, _ = ads.parse_manifest(manifest([5, 5, 5]), self.original['url'])
        cache = ads.SegmentCache(self.root, 'a' * 64, {})
        scanner = runtime.FeatureScanner(self.root, self.root, cache, regions, '/ffmpeg', '/ffprobe', probe)
        group = next(group for group in runtime.candidates(regions) if group['region_indices'] == [1])
        def loaded(*args, **kwargs):
            return loaded_media()
        def sampled(media, role, *args, **kwargs):
            samples = []
            for fraction in kwargs['fractions']:
                sample = features(commercial=True)['ocr']['candidate']['samples'][0]
                sample.update(fraction=fraction, time_seconds=fraction * 10)
                if role != 'candidate':
                    sample['observations'] = []
                samples.append(sample)
            return {'status': 'ok', 'samples': samples}
        with patch.object(scanner, 'remux', return_value=self.root / 'candidate.ts'), \
             patch.object(probe, 'load_slice', side_effect=loaded), \
             patch.object(probe, 'extract_ocr', side_effect=sampled) as extract, \
             patch.object(probe, 'bridge_evidence', return_value={'status': 'unknown'}):
            result = scanner.process(group)
        calls = {call.args[1]: call for call in extract.call_args_list}
        self.assertEqual(len(extract.call_args_list), 3)
        self.assertEqual(calls['candidate'].kwargs, {'batch': True, 'fractions': [.05, .1], 'max_width': 960})
        for role in ('before', 'after'):
            self.assertEqual(calls[role].kwargs, {'fractions': [.5], 'max_width': 960})
        self.assertEqual(result['ocr_sampling'], expected_profile)
        self.assertEqual(result['errors'], [])
        self.assertEqual(result['candidate_gate'], 'valid')
        self.assertEqual(result['ocr']['candidate']['status'], 'ok')
        weighted = scorer.score_case(result)
        text = weighted['families']['P']
        self.assertEqual(scorer.SETTINGS['ocr_expected_frames'], 3)
        self.assertEqual(scorer.SETTINGS['ocr_confidence_power'], .5)
        self.assertEqual(scorer.SETTINGS['weights'], {'S': 40, 'A': 25, 'P': 25})
        self.assertEqual(text['coverage'], {'available': 2, 'expected': 3,
                                           'outside_available': 2, 'outside_expected': 2})
        self.assertAlmostEqual(text['points'], 50 / 3)
        self.assertEqual(text['missing_reasons'], ['missing_candidate_ocr_sample_times'])
        # Reduced coverage is planned missing evidence, not a failed OCR provider.
        plan = self.run_scan(lambda _: copy.deepcopy(result))
        diagnostics = dict(expected_profile, planned_candidate_frames=2, scorer_expected_candidate_frames=3,
                           temporal_persistence_min_distinct_times=2)
        self.assertEqual(plan['ocr_sampling'], diagnostics)
        self.assertTrue(plan['coverage']['reduced_ocr_sampling'])
        self.assertEqual(plan['coverage']['ocr_failure_count'], 0)
        self.assertTrue(plan['coverage']['analysis_complete'])
        latest = json.loads((self.root / 'weighted-latest.json').read_text())
        audit = json.loads((self.root / 'weighted-features.json').read_text())
        self.assertEqual(latest['ocr_sampling'], diagnostics)
        self.assertEqual(latest['features']['ocr_sampling'], expected_profile)
        self.assertEqual(audit['ocr_sampling'], diagnostics)
        self.assertTrue(all(case['features']['ocr_sampling'] == expected_profile for case in audit['cases']))

    def test_host_defaults_and_duplicate_native_time_coverage_remain_unchanged(self):
        self.assertEqual(probe.POLICY_SETTINGS['ocr_candidate_fractions'], [.1, .5, .9])
        self.assertEqual(probe.POLICY_SETTINGS['ocr_context_fractions'], [.5])
        self.assertEqual(probe.POLICY_SETTINGS['ocr_max_width'], 1920)
        media = {'path': Path('unused.ts'), 'start': 0, 'duration': 10, 'origin': 0, 'times': [.8]}
        observation = features(commercial=True)['ocr']['candidate']['samples'][0]['observations']
        with patch.object(probe, 'extract_png_batch', return_value={0: b'png'}), \
             patch.object(probe, 'ocr_png', return_value={'engine': 'test', 'observations': observation}):
            ocr = probe.extract_ocr(media, 'candidate', '/ffmpeg', None, batch=True,
                fractions=runtime.OCR_SAMPLING_PROFILE['candidate_fractions'], max_width=960)
        self.assertEqual([sample['fraction'] for sample in ocr['samples']], [.05, .1])
        self.assertEqual([sample['native_frame_index'] for sample in ocr['samples']], [0, 0])
        self.assertEqual([sample['time_seconds'] for sample in ocr['samples']], [.8, .8])
        candidate = features(commercial=True)
        candidate['ocr']['candidate'] = ocr
        text = scorer.text_score(candidate)
        self.assertEqual(text['coverage']['available'], 1)
        self.assertEqual(text['coverage']['expected'], 3)
        self.assertAlmostEqual(text['points'], 25 / 3)

    def test_score_bound_skips_candidate_only_and_records_incomplete_ocr_evidence(self):
        _, regions, _ = ads.parse_manifest(manifest([5, 5, 5]), self.original['url'])
        cache = ads.SegmentCache(self.root, 'a' * 64, {})
        scanner = runtime.FeatureScanner(self.root, self.root, cache, regions,
            '/ffmpeg', '/ffprobe', probe, scorer=scorer)
        group = next(group for group in runtime.candidates(regions) if group['region_indices'] == [1])
        def loaded(*args, **kwargs):
            self.assertEqual(kwargs, {'summary_mode': 'weighted_decision'})
            return loaded_media()
        with patch.object(scanner, 'remux', return_value=self.root / 'candidate.ts'), \
             patch.object(probe, 'load_slice', side_effect=loaded), \
             patch.object(probe, 'bridge_evidence', return_value={'status': 'unknown'}), \
             patch.object(probe, 'extract_ocr', return_value={'status': 'ok', 'samples': []}) as extract:
            result = scanner.process(group)
            self.assertEqual([call.args[1] for call in extract.call_args_list], ['before', 'after'])
            self.assertEqual(result['ocr']['candidate']['status'], 'skipped')
            self.assertEqual(result['errors'], [])
            self.assertAlmostEqual(result['ocr_evaluation']['score_upper_bound'], 50, places=6)
            self.assertEqual(result['feature_summary_profile'], 'weighted_decision')
            scanner.process(group)
            self.assertEqual(extract.call_count, 2)  # Context OCR is already complete and cached.
        plan = self.run_scan(lambda _: copy.deepcopy(result))
        self.assertEqual(plan['coverage']['candidate_ocr_skipped_by_score_bound'], len(plan['regions']))
        self.assertFalse(plan['coverage']['candidate_ocr_evidence_complete'])
        self.assertFalse(plan['cost']['checkpoint']['raw_evidence_complete'])
        self.assertTrue(plan['cost']['checkpoint']['recorded_evidence_complete'])
        self.assertTrue(plan['coverage']['analysis_complete'])
        self.assertEqual(plan['coverage']['ocr_failure_count'], 0)
        self.assertTrue(all(not row['score_is_exact'] for row in plan['regions']))
        latest = json.loads((self.root / 'weighted-latest.json').read_text())
        self.assertFalse(latest['weighted']['score_is_exact'])
        self.assertAlmostEqual(latest['weighted']['score_upper_bound'], 50, places=6)
        # Journal restoration retains the explicit bound and its evidence coverage.
        self.plan.unlink()
        with patch.object(runtime, 'load_policy', return_value=({}, 'b' * 64)), \
             patch.object(runtime, 'feature_modules', return_value=(probe, scorer)), \
             patch.object(runtime.FeatureScanner, 'process', side_effect=AssertionError('repeated completed candidate')):
            restored = runtime.classify(self.input, self.catalog, self.policy, self.plan, '/ffmpeg', '/ffprobe')
        self.assertEqual(restored['coverage'], plan['coverage'])
        self.assertEqual(restored['cost']['checkpoint']['new_candidates'], 0)

    def test_possible_threshold_candidate_still_uses_ocr(self):
        _, regions, _ = ads.parse_manifest(manifest([5, 5, 5]), self.original['url'])
        scanner = runtime.FeatureScanner(self.root, self.root, ads.SegmentCache(self.root, 'a' * 64, {}),
            regions, '/ffmpeg', '/ffprobe', probe, scorer=scorer)
        group = next(group for group in runtime.candidates(regions) if group['region_indices'] == [1])
        with patch.object(scanner, 'remux', return_value=self.root / 'candidate.ts'), \
             patch.object(probe, 'load_slice', return_value=loaded_media()), \
             patch.object(probe, 'bridge_evidence', return_value={'status': 'unknown'}), \
             patch.object(scorer, 'scene_score', return_value={'points': 5}), \
             patch.object(scorer, 'audio_score', return_value={'points': 0}), \
             patch.object(probe, 'extract_ocr', return_value={'status': 'ok', 'samples': []}) as extract:
            result = scanner.process(group)
        self.assertEqual([call.args[1] for call in extract.call_args_list], ['before', 'after', 'candidate'])
        self.assertEqual(result['ocr_evaluation']['status'], 'evaluated')
        self.assertTrue(result['ocr_evaluation']['score_is_exact'])

    def test_disjoint_score_ranking_audits_overlaps_and_does_not_merge_ads(self):
        def row(start, end, score):
            return {'region_indices': list(range(start, end + 1)), 'segment_indices': list(range(start, end + 1)),
                    'duration_seconds': (end - start + 1) * 10, 'score': score,
                    'decision': 'experimental_ad_candidate'}
        rows = [row(1, 1, 68), row(0, 1, 70), row(2, 2, 60)]
        groups = runtime.select_groups(rows)
        self.assertEqual([g['region_indices'] for g in groups], [[0, 1], [2]])
        self.assertEqual(rows[0]['selection'], 'preserved_overlapping_higher_ranked_range')
        self.assertEqual(rows[0]['overlapping_passing_ranges'], [[0, 1]])

    def test_weighted_cache_budget_is_explicit_and_legacy_default_is_unchanged(self):
        cache = ads.SegmentCache(self.root, 'a' * 64, {}, max_total_bytes=ads.WEIGHTED_MAX_TOTAL_BYTES)
        self.assertEqual(cache.max_total_bytes, 2 * 1024 ** 3)
        self.assertEqual(ads.SegmentCache(self.root, 'a' * 64, {}).max_total_bytes, 128 * 1024 ** 2)
        with self.assertRaises(ads.FilterError):
            ads.SegmentCache(self.root, 'a' * 64, {}, max_total_bytes=ads.WEIGHTED_MAX_TOTAL_BYTES + 1)

    def test_runtime_policy_hashes_and_catalog_flag_are_checked(self):
        directory = self.root / 'runtime'
        directory.mkdir()
        names = {'weighted_hls_ads.py': ASSETS / 'weighted_hls_ads.py',
                 'ad_analysis_checkpoint.py': ASSETS / 'ad_analysis_checkpoint.py',
                 'hls_ad_filter.py': ASSETS / 'hls_ad_filter.py', 'hls_resume.py': ASSETS / 'hls_resume.py',
                 'hls_media_validation.py': ASSETS / 'hls_media_validation.py',
                 'hls_ad_fingerprints.json': self.catalog,
                 'probe_hls_ad_features.py': SCRIPTS / 'probe-hls-ad-features.py',
                 'score_hls_ad_features.py': SCRIPTS / 'score-hls-ad-features.py'}
        for name, source in names.items():
            (directory / name).write_bytes(source.read_bytes())
        runtime_asset = directory / 'weighted_hls_ads.py'
        new_profile_asset = runtime_asset.read_bytes()
        old_profile_asset = new_profile_asset.replace(b"'front-two-960-v1'", b"'front-only-960-v1'").replace(
            b"'candidate_fractions': [.05, .1]", b"'candidate_fractions': [.1]")
        self.assertNotEqual(old_profile_asset, new_profile_asset)
        runtime_asset.write_bytes(old_profile_asset)
        policy = {'schema': 1, 'policy': runtime.POLICY, 'known_catalog_enabled': False,
                  'ocr_engine': runtime.OCR_ENGINE,
                  'assets': {name: ads.digest((directory / name).read_bytes()) for name in names}}
        path = directory / 'policy.json'
        path.write_text(json.dumps(policy))
        old_policy_sha = runtime.load_policy(path, False)[1]
        self.assertEqual(old_policy_sha, ads.digest(path.read_bytes()))
        with self.assertRaises(ads.FilterError):
            runtime.load_policy(path, True)
        _, regions, _ = ads.parse_manifest(self.original['manifest'], self.original['url'])
        candidates = list(runtime.candidates(regions, media_limit=0))
        old_journal = runtime.CandidateJournal(self.root, self.original, old_policy_sha, candidates)
        old_journal.restore()
        raw = features()
        weighted = scorer.score_case(raw)
        row = dict(candidates[0], score=weighted['score'], decision=weighted['decision'],
                   status='scored', errors=[], expected_absence_count=0)
        old_journal.commit(0, raw, weighted, row, 1)
        saved = old_journal._entry(0).read_bytes()
        runtime_asset.write_bytes(new_profile_asset)
        with self.assertRaises(ads.FilterError):
            runtime.load_policy(path, False)
        policy['assets']['weighted_hls_ads.py'] = ads.digest(new_profile_asset)
        path.write_text(json.dumps(policy))
        new_policy_sha = runtime.load_policy(path, False)[1]
        self.assertNotEqual(new_policy_sha, old_policy_sha)
        new_journal = runtime.CandidateJournal(self.root, self.original, new_policy_sha, candidates)
        restored = new_journal.restore()
        self.assertEqual(restored['completed'], 0)
        self.assertIn('runtime_policy_changed', restored['invalidation_reason'])
        self.assertNotEqual(new_journal.directory, old_journal.directory)
        self.assertEqual(old_journal._entry(0).read_bytes(), saved)
        (directory / 'score_hls_ad_features.py').write_text('changed')
        with self.assertRaises(ads.FilterError):
            runtime.load_policy(path, False)

    def test_candidate_cache_budget_failure_is_unknown(self):
        _, regions, _ = ads.parse_manifest(manifest([5, 5, 5]), self.original['url'])
        cache = ads.SegmentCache(self.root, 'a' * 64, {})
        scanner = runtime.FeatureScanner(self.root, self.root, cache, regions, '/ffmpeg', '/ffprobe', probe)
        with patch.object(cache, 'fetch', side_effect=ads.Unknown('download_byte_budget')):
            result = scanner.process(next(runtime.candidates(regions)))
        self.assertEqual(result['candidate_gate'], 'unknown')
        self.assertTrue(any('download_byte_budget' in error for error in result['errors']))
        self.assertEqual(scorer.score_case(result)['decision'], 'unknown')

    def test_ocr_bridge_uuid_protocol_png_hash_reuse_and_cleanup(self):
        now = [0.0]
        profile = runtime.WallTimeProfile(lambda: now[0])
        bridge = runtime.OCRBridge(self.root, probe, profiler=profile)
        def reply(message, **kwargs):
            now[0] += 2
            token = message.split()[1]
            self.assertRegex(token, r'^[0-9a-f]{32}$')
            self.assertTrue((self.root / ('ocr-' + token + '.png')).exists())
            (self.root / ('ocr-' + token + '.json')).write_text(json.dumps(
                {'engine': runtime.OCR_ENGINE, 'observations': []}))
        with patch('builtins.print', side_effect=reply):
            first = bridge(b'pngbytes')
            self.assertEqual(bridge(b'pngbytes'), first)
        self.assertEqual(bridge.requests, 1)
        self.assertEqual(bridge.reused, 1)
        self.assertEqual(profile.snapshot()['categories']['ocr_bridge_wait'], {'seconds': 2, 'calls': 1})
        self.assertFalse(list(self.root.glob('ocr-*')))

    def test_ocr_error_does_not_become_empty_success(self):
        bridge = runtime.OCRBridge(self.root, probe)
        def reply(message, **kwargs):
            token = message.split()[1]
            (self.root / ('ocr-' + token + '.json')).write_text('{"error":"model unavailable"}')
        with patch('builtins.print', side_effect=reply) as requests:
            for _ in range(2):
                with self.assertRaises(probe.Unknown):
                    bridge(b'pngbytes')
            self.assertEqual(requests.call_count, 1)
        self.assertFalse(list(self.root.glob('ocr-*')))

    @unittest.skipUnless(shutil.which('ffmpeg') and shutil.which('ffprobe'), 'requires host FFmpeg')
    def test_region_assembly_preserves_native_frames_and_actual_context_tail(self):
        source = self.root / 'sources'
        source.mkdir()
        lines = ['#EXTM3U', '#EXT-X-TARGETDURATION:2']
        for region, size in enumerate(('128x72', '256x144', '128x72', '256x144')):
            if region:
                lines.append('#EXT-X-DISCONTINUITY')
            subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i',
                'testsrc2=s=%s:r=10:d=2' % size, '-f', 'lavfi', '-i', 'sine=frequency=440:duration=2',
                '-c:v', 'libx264', '-g', '10', '-bf', '0', '-c:a', 'aac', '-hls_time', '1',
                '-hls_list_size', '0', '-hls_segment_filename', str(source / ('r%d-%%d.ts' % region)),
                str(source / ('r%d.m3u8' % region))], check=True, timeout=20)
            # Deliberately rounded-down EXTINF values must not trim native tails.
            for part in range(2):
                lines += ['#EXTINF:0.9,', 'r%d-%d.ts' % (region, part)]
        text = '\n'.join(lines + ['#EXT-X-ENDLIST']) + '\n'
        _, regions, segments = ads.parse_manifest(text, self.original['url'])
        cache = ads.SegmentCache(self.root, 'a' * 64, {})
        for segment in segments:
            payload = (source / segment['url'].rsplit('/', 1)[-1]).read_bytes()
            entry = {'url': segment['url'], 'path': 'ad-cache/' + ads.digest(segment['url'].encode()) + '.ts',
                     'size': len(payload), 'sha256': ads.digest(payload)}
            ads.cache_path(self.root, entry).write_bytes(payload)
            cache.entries[segment['url']] = entry
        scanner = runtime.FeatureScanner(self.root, self.root, cache, regions,
                                         shutil.which('ffmpeg'), shutil.which('ffprobe'), probe)
        starts = {r['segments'][0]['index'] for r in regions}
        references = []
        for region in regions:
            path = scanner.remux(region['segments'], 'reference')
            media = probe.load_slice({'path': str(path)}, 'candidate', self.root, scanner.ffmpeg, scanner.ffprobe)
            references.append(media['frames'])
        self.assertEqual([len(frames) for frames in references], [20] * 4)
        for group in runtime.candidates(regions):
            path = scanner.remux(group['segments'], 'candidate', starts)
            actual = probe.load_slice({'path': str(path)}, 'candidate', self.root, scanner.ffmpeg, scanner.ffprobe)
            expected = [frame for index in group['region_indices'] for frame in references[index]]
            self.assertEqual(actual['frames'], expected, group['region_indices'])
            scanner.release(path)
        with patch.object(probe, 'ocr_png', return_value={'engine': 'synthetic', 'observations': []}):
            before, _ = scanner.outside(0, 'before')
        source_duration = float(before['public']['metadata']['format']['duration'])
        self.assertAlmostEqual(before['start'] + before['duration'], source_duration, places=6)
        self.assertGreater(source_duration, before['public']['source_timing']['nominal_extinf_seconds'])
        self.assertEqual(before['frames'][-1], references[0][-1])
        self.assertGreater(scanner.region_remux_reuse, 0)
        self.assertLessEqual(len(scanner.region_files), runtime.MAX_REGION_FILES)

    @unittest.skipUnless(shutil.which('ffmpeg') and shutil.which('ffprobe'), 'requires host FFmpeg')
    def test_retained_media_color_change_preserves_monotonic_decoded_video_pts(self):
        ffmpeg, ffprobe = shutil.which('ffmpeg'), shutil.which('ffprobe')
        for index in range(2):
            color = [] if index == 0 else ['-color_range', 'tv', '-colorspace', 'bt709',
                                           '-color_primaries', 'bt709', '-color_trc', 'bt709']
            subprocess.run([ffmpeg, '-nostdin', '-v', 'error', '-f', 'lavfi', '-i',
                'testsrc2=s=128x72:r=25:d=1', '-f', 'lavfi', '-i', 'sine=frequency=440:duration=1',
                '-c:v', 'libx264', '-threads', '1', '-g', '25', '-bf', '2', '-c:a', 'aac',
                *color, '-f', 'mpegts', str(self.root / ('color%d.ts' % index))],
                check=True, capture_output=True, timeout=20)
        playlist = self.root / 'color-change.m3u8'
        playlist.write_text('#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\ncolor0.ts\n'
                            '#EXT-X-DISCONTINUITY\n#EXTINF:1,\ncolor1.ts\n#EXT-X-ENDLIST\n')
        target, result = self.root / 'retained-color-change.mp4', self.root / 'validation.json'
        subprocess.run([ffmpeg, '-nostdin', '-v', 'error', '-i', str(playlist),
                        '-map', '0', '-c', 'copy', str(target)],
                       check=True, capture_output=True, timeout=20)
        packets = ads.run_json([ffprobe, '-v', 'error', '-show_packets', '-show_entries',
                                'packet=stream_index,dts', '-of', 'json', str(target)])['packets']
        frames = ads.run_json([ffprobe, '-v', 'error', '-show_frames', '-show_entries',
            'frame=stream_index,media_type,pts,color_range,color_space', '-of', 'json', str(target)])['frames']
        for entries, timestamp in ((packets, 'dts'), (frames, 'pts')):
            previous = {}
            for entry in entries:
                stream, current = entry['stream_index'], int(entry[timestamp])
                self.assertGreater(current, previous.get(stream, -float('inf')))
                previous[stream] = current
            self.assertEqual(set(previous), {0, 1})
        video = [frame for frame in frames if frame['media_type'] == 'video']
        self.assertEqual(len(video), 50)
        self.assertIn(video[0].get('color_space'), (None, 'unknown'))
        self.assertEqual((video[-1]['color_range'], video[-1]['color_space']), ('tv', 'bt709'))
        data = runtime.validate_media(target, 2, result, ffmpeg, ffprobe)
        self.assertEqual(data['status'], 'valid')
        self.assertTrue(data['full_av_decode'])
        self.assertTrue(data['packet_dts_monotonic'])

    @unittest.skipUnless(shutil.which('ffmpeg') and shutil.which('ffprobe'), 'requires host FFmpeg')
    def test_retained_media_validation_full_decode_and_duration(self):
        target, result = self.root / 'retained.mp4', self.root / 'validation.json'
        subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'testsrc2=s=128x72:r=10:d=2',
                        '-f', 'lavfi', '-i', 'sine=frequency=440:duration=2', '-c:v', 'libx264', '-c:a', 'aac',
                        '-shortest', str(target)], check=True)
        data = runtime.validate_media(target, 2, result, shutil.which('ffmpeg'), shutil.which('ffprobe'))
        self.assertEqual(data['status'], 'valid')
        self.assertTrue(data['full_av_decode'])
        with self.assertRaises(ads.FilterError):
            runtime.validate_media(target, 20, result, shutil.which('ffmpeg'), shutil.which('ffprobe'))
        self.assertEqual(json.loads(result.read_text())['status'], 'unknown')
        self.assertEqual(json.loads(result.read_text())['reason'], 'retained_duration_mismatch')

    @unittest.skipUnless(shutil.which('ffmpeg') and shutil.which('ffprobe'), 'requires host FFmpeg')
    def test_retained_media_validation_rejects_late_h264_decode_errors(self):
        ffmpeg, ffprobe = shutil.which('ffmpeg'), shutil.which('ffprobe')
        target, result = self.root / 'corrupt-slice.mp4', self.root / 'validation.json'
        subprocess.run([ffmpeg, '-nostdin', '-v', 'error', '-f', 'lavfi', '-i',
            'testsrc2=s=128x72:r=25:d=4', '-f', 'lavfi', '-i', 'sine=frequency=440:duration=4',
            '-c:v', 'libx264', '-threads', '1', '-g', '25', '-bf', '2', '-c:a', 'aac', str(target)],
            check=True, capture_output=True, timeout=20)
        self.assertEqual(runtime.validate_media(target, 4, result, ffmpeg, ffprobe)['status'], 'valid')
        packets = ads.run_json([ffprobe, '-v', 'error', '-show_packets', '-show_entries',
                               'packet=stream_index,pos,size,flags', '-of', 'json', str(target)])['packets']
        packet = next(packet for packet in reversed(packets)
                      if packet['stream_index'] == 0 and 'K' in packet['flags'])
        payload = bytearray(target.read_bytes())
        offset = int(packet['pos'])
        end = offset + int(packet['size'])
        changed = False
        # Leave the container, packet lengths and AVC NAL headers readable; damage a late coded slice.
        while offset + 4 < end:
            size = int.from_bytes(payload[offset:offset + 4], 'big')
            self.assertGreater(size, 0)
            self.assertLessEqual(offset + 4 + size, end)
            if payload[offset + 4] & 31 in (1, 5) and size > 12:
                start, stop = offset + 12, offset + 4 + size
                payload[start:stop] = b'\0' * (stop - start)
                changed = True
            offset += 4 + size
        self.assertTrue(changed)
        target.write_bytes(payload)
        with self.assertRaises(ads.FilterError):
            runtime.validate_media(target, 4, result, ffmpeg, ffprobe)
        rejected = json.loads(result.read_text())
        self.assertEqual(rejected['status'], 'unknown')
        self.assertTrue(rejected['reason'].startswith('retained_full_av_decode_failed:'), rejected)

    @unittest.skipUnless(shutil.which('ffmpeg') and shutil.which('ffprobe'), 'requires host FFmpeg')
    def test_audio_only_retained_validation_requires_explicit_mode(self):
        result = self.root / 'validation.json'
        for extension, codec in [('m4a', 'aac'), ('mp3', 'libmp3lame'), ('flac', 'flac'), ('wav', 'pcm_s16le')]:
            with self.subTest(extension=extension):
                target = self.root / ('retained.' + extension)
                subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'sine=frequency=440:duration=2',
                                '-c:a', codec, str(target)], check=True)
                data = runtime.main(['--validate-media', str(target), '--expected-duration', '2',
                    '--validation-result', str(result), '--ffmpeg', shutil.which('ffmpeg'),
                    '--ffprobe', shutil.which('ffprobe'), '--audio-only'])
                self.assertEqual(data['status'], 'valid')
                self.assertEqual(data['media_mode'], 'audio')
                self.assertTrue(data['full_av_decode'])
                self.assertTrue(data['packet_dts_monotonic'])
                with self.assertRaises(ads.FilterError):
                    runtime.validate_media(target, 2, result, shutil.which('ffmpeg'), shutil.which('ffprobe'))



@unittest.skipUnless(shutil.which('ffmpeg') and shutil.which('ffprobe'), 'requires host FFmpeg')
class DownloadValidationReuseTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixtures = tempfile.TemporaryDirectory()
        cls.fixture_root = Path(cls.fixtures.name)
        cls.source = cls.fixture_root / 'source.ts'
        cls.output = cls.fixture_root / 'retained.mp4'
        cls.ffmpeg, cls.ffprobe = shutil.which('ffmpeg'), shutil.which('ffprobe')
        subprocess.run([cls.ffmpeg, '-nostdin', '-v', 'error', '-f', 'lavfi', '-i',
            'testsrc2=s=128x72:r=25:d=4', '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000:duration=4',
            '-c:v', 'libx264', '-threads', '1', '-g', '25', '-bf', '2', '-c:a', 'aac',
            '-f', 'mpegts', str(cls.source)], check=True, capture_output=True, timeout=20)
        subprocess.run([cls.ffmpeg, '-nostdin', '-v', 'error', '-i', str(cls.source), '-c', 'copy',
                        str(cls.output)], check=True, capture_output=True, timeout=20)

    @classmethod
    def tearDownClass(cls):
        cls.fixtures.cleanup()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / '.resume'
        self.root.mkdir()
        self.session = self.root / 'session.json'
        token, identity = '11111111-2222-3333-4444-555555555555', 'a' * 64
        self.session.write_text(json.dumps({'schema': 1, 'identity': identity,
            'manifest': '#EXTM3U\n#EXTINF:2,\na.ts\n#EXTINF:2,\nb.ts\n#EXT-X-ENDLIST\n',
            'media_validation': {'schema': 1, 'token': token, 'media_mode': 'video'}}))
        validator = download_validation.DownloadValidator(self.ffmpeg, identity, token)
        try:
            validator.notify(self.source, self.source.stat().st_size, 2, 4)
            self.receipt = validator.finish()
        finally:
            validator.close()
        self.assertEqual(self.receipt['status'], 'valid', self.receipt)
        self.receipt_file = self.root / 'download-validation.json'
        self.receipt_file.write_text(json.dumps(self.receipt))
        self.target = self.root.parent / 'retained.mp4'
        shutil.copyfile(self.output, self.target)

    def validate(self):
        return runtime.validate_media(self.target, 4, self.root / 'validation.json',
                                      self.ffmpeg, self.ffprobe, session_path=self.session)

    def test_complete_matching_download_decode_avoids_final_decoder(self):
        native_run = subprocess.run
        def no_final_decode(command, *args, **kwargs):
            self.assertNotIn('null', command, 'A matched final file must not be decoded again')
            return native_run(command, *args, **kwargs)
        with patch.object(runtime.subprocess, 'run', side_effect=no_final_decode):
            value = self.validate()
        self.assertEqual(value['status'], 'valid')
        self.assertEqual(value['decode_method'], 'during_download')
        self.assertFalse(value['final_full_av_decode'])
        self.assertTrue(value['full_av_decode'])
        self.assertTrue(value['packet_dts_monotonic'])

    def test_incomplete_stale_and_changed_engine_proofs_require_final_decode(self):
        changes = [('token', 'different execution'), ('identity', 'b' * 64), ('segments', 1),
                   ('eof', False), ('full_decode', False), ('source_bytes', 0),
                   ('source_sha256', 'invalid'), ('validator_sha256', 'c' * 64),
                   ('ffmpeg_sha256', 'd' * 64), ('fingerprints', {})]
        for key, changed in changes:
            with self.subTest(key=key):
                value = dict(self.receipt, **{key: changed})
                self.receipt_file.write_text(json.dumps(value))
                result = self.validate()
                self.assertEqual(result['decode_method'], 'after_download')
                self.assertTrue(result['final_full_av_decode'])

    def test_missing_malformed_and_unavailable_receipts_fall_back(self):
        for contents in (None, 'not json', '[]', 'null', '{"status":"unavailable"}'):
            with self.subTest(contents=contents):
                if contents is None:
                    self.receipt_file.unlink()
                else:
                    self.receipt_file.write_text(contents)
                result = self.validate()
                self.assertEqual(result['decode_method'], 'after_download')
                self.assertTrue(result['final_full_av_decode'])

    def test_final_coded_slice_corruption_cannot_reuse_clean_download_proof(self):
        packets = ads.run_json([self.ffprobe, '-v', 'error', '-show_packets', '-show_entries',
                               'packet=stream_index,pos,size,flags', '-of', 'json', str(self.target)])['packets']
        packet = next(p for p in reversed(packets) if p['stream_index'] == 0 and 'K' in p['flags'])
        payload = bytearray(self.target.read_bytes())
        offset, end = int(packet['pos']), int(packet['pos']) + int(packet['size'])
        changed = False
        while offset + 4 < end:
            size = int.from_bytes(payload[offset:offset + 4], 'big')
            if payload[offset + 4] & 31 in (1, 5) and size > 12:
                payload[offset + 12:offset + 4 + size] = b'\0' * (size - 8)
                changed = True
            offset += 4 + size
        self.assertTrue(changed)
        self.target.write_bytes(payload)
        with self.assertRaises(ads.FilterError):
            self.validate()
        value = json.loads((self.root / 'validation.json').read_text())
        self.assertEqual(value['status'], 'unknown')
        self.assertEqual(value['decode_method'], 'after_download')
        self.assertTrue(value['reason'].startswith('retained_full_av_decode_failed:'), value)

    def test_presentation_time_damage_cannot_reuse_identical_encoded_content(self):
        # Keep sample payloads, decode timestamps and container duration intact;
        # corrupt only the final composition-time offset in the video track.
        payload = bytearray(self.target.read_bytes())
        def ctts(start, end):
            while start + 8 <= end:
                size = int.from_bytes(payload[start:start + 4], 'big')
                kind = payload[start + 4:start + 8]
                if size < 8:
                    break
                if kind == b'ctts':
                    return start
                if kind in (b'moov', b'trak', b'mdia', b'minf', b'stbl'):
                    found = ctts(start + 8, start + size)
                    if found is not None:
                        return found
                start += size
        offset = ctts(0, len(payload))
        self.assertIsNotNone(offset)
        count = int.from_bytes(payload[offset + 12:offset + 16], 'big')
        entry = offset + 16 + (count - 1) * 8 + 4
        payload[entry:entry + 4] = b'\0' * 4
        self.target.write_bytes(payload)
        with self.assertRaises(ads.FilterError):
            self.validate()
        value = json.loads((self.root / 'validation.json').read_text())
        self.assertEqual(value['status'], 'unknown')
        self.assertEqual(value['decode_method'], 'after_download')
        self.assertEqual(value['download_validation_reason'], 'encoded_content_mismatch_or_unavailable')
        self.assertTrue(value['reason'].startswith('retained_full_av_decode_failed:'), value)


if __name__ == '__main__':
    unittest.main()
