"""Candidate journal contracts; no FFmpeg, OCR engine, network or device."""
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'main/assets'))
import ad_analysis_checkpoint as checkpoint


class CandidateJournalTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.source = {'identity': 'a' * 64, 'manifest': '#EXTM3U\n',
                       'url': 'https://example.test/a.m3u8', 'headers': {'Referer': 'https://example.test/'}}
        self.candidates = [{'region_indices': [index], 'segment_indices': [index],
                            'duration_seconds': 4.0, 'source_detail': {'url': 'https://example.test/%d.ts' % index}}
                           for index in range(4)]

    def journal(self, **kwargs):
        return checkpoint.CandidateJournal(self.root, self.source, 'b' * 64, self.candidates, **kwargs)

    def evidence(self, index, valid=True):
        features = {'inputs': {'candidate': {'status': 'ok' if valid else 'unknown'}},
                    'candidate_gate': 'valid' if valid else 'unknown', 'errors': [],
                    'ocr': {'candidate': {'samples': [{'text': 'all original evidence', 'box': [.1, .2, .3, .4]}]}},
                    'expected_absences': []}
        weighted = {'score': 60.0, 'candidate_source_gate': {'valid': valid},
                    'decision': 'experimental_ad_candidate' if valid else 'unknown',
                    'families': {'S': {'points': 40}, 'C': {'points': 20}}}
        row = {k: self.candidates[index][k] for k in ('region_indices', 'segment_indices', 'duration_seconds')}
        row.update(score=60.0, decision=weighted['decision'], status='scored' if valid else 'unknown',
                   errors=[], expected_absence_count=0)
        return features, weighted, row

    def commit(self, journal, index, seconds=3.5):
        return journal.commit(index, *self.evidence(index), seconds)

    def test_empty_then_resume_exact_prefix_without_decode_ocr_or_scoring(self):
        now = [10.0]
        calls = {'decode': [], 'ocr': [], 'score': []}
        def run(journal, stop_after=None):
            restored = journal.restore()
            rows = [item['payload']['row'] for item in restored['records']]
            for index in range(restored['completed'], len(self.candidates)):
                start = now[0]
                for operation in calls:
                    calls[operation].append(index)
                    now[0] += 1.0
                if index == stop_after:
                    break  # K+1 interrupted before its atomic completion boundary.
                journal.commit(index, *self.evidence(index), now[0] - start)
                rows.append(self.evidence(index)[2])
            return restored, rows
        first = self.journal()
        restored, _ = run(first, stop_after=2)
        self.assertEqual(restored['completed'], 0)
        original = copy.deepcopy(first.records)
        calls = {operation: [] for operation in calls}
        restored, rows = run(self.journal())
        self.assertEqual(restored['completed'], 2)
        self.assertEqual(restored['saved_elapsed_seconds'], 6.0)
        self.assertEqual(restored['records'], original)
        self.assertEqual(calls, {'decode': [2, 3], 'ocr': [2, 3], 'score': [2, 3]})
        self.assertEqual(len(rows), 4)
        self.assertEqual(self.journal().restore()['saved_elapsed_seconds'], 12)

    def test_scorer_coordinate_tuples_are_losslessly_normalized_for_json(self):
        journal = self.journal()
        journal.restore()
        evidence = self.evidence(0)
        evidence[1]['families']['C']['coordinates'] = (.1, .2, .8, .9)
        committed = journal.commit(0, *evidence, 1)
        restored = self.journal().restore()
        self.assertEqual(restored['records'][0], committed)
        self.assertEqual(committed['payload']['weighted']['families']['C']['coordinates'], [.1, .2, .8, .9])

    def test_corrupt_middle_preserves_prefix_and_original_raw_evidence(self):
        journal = self.journal()
        journal.restore()
        for index in range(4):
            self.commit(journal, index)
        first = journal._entry(0).read_bytes()
        corrupt = journal._entry(1)
        corrupt.write_bytes(b'{"payload": "truncated')
        old_suffix = journal._entry(2).read_bytes()
        resumed = self.journal()
        restored = resumed.restore()
        self.assertEqual(restored['completed'], 1)
        self.assertIn('invalid_candidate_1', restored['invalidation_reason'])
        self.assertEqual(restored['saved_elapsed_seconds'], 3.5)
        self.assertEqual(journal._entry(0).read_bytes(), first)
        self.assertEqual(corrupt.read_bytes(), b'{"payload": "truncated')
        for index in range(1, 4):
            self.commit(resumed, index)
        rejected = list(journal.directory.glob('*.rejected-*'))
        self.assertEqual(len(rejected), 3)
        self.assertIn(old_suffix, [p.read_bytes() for p in rejected])
        self.assertEqual(self.journal().restore()['completed'], 4)

    def test_missing_middle_and_hashchain_damage_reuse_only_prefix(self):
        for damage in ('missing', 'hashchain', 'checksum'):
            with self.subTest(damage=damage):
                journal = self.journal()
                journal.restore()
                for index in range(len(journal.records), 4):
                    self.commit(journal, index)
                path = journal._entry(1)
                if damage == 'missing':
                    path.unlink()
                else:
                    value = json.loads(path.read_text())
                    value['previous_sha256' if damage == 'hashchain' else 'payload_sha256'] = '0' * 64
                    path.write_text(json.dumps(value))
                restored = self.journal().restore()
                self.assertEqual(restored['completed'], 1)
                self.assertIn('candidate_1', restored['invalidation_reason'])

    def test_source_policy_and_entire_ordered_list_changes_reuse_none(self):
        journal = self.journal()
        journal.restore()
        self.commit(journal, 0)
        original = journal._entry(0).read_bytes()
        changes = [('manifest', lambda: self.source.update(manifest='#EXTM3U\n#changed'), 'source'),
                   ('url', lambda: self.source.update(url='https://example.test/other.m3u8'), 'source'),
                   ('headers', lambda: self.source['headers'].update(Referer='new'), 'source'),
                   ('list', lambda: self.candidates.reverse(), 'candidates')]
        for _, change, reason in changes:
            change()
            restored = self.journal().restore()
            self.assertEqual(restored['completed'], 0)
            self.assertIn(reason + '_changed', restored['invalidation_reason'])
        policy = checkpoint.CandidateJournal(self.root, self.source, 'c' * 64, self.candidates)
        self.assertIn('runtime_policy_changed', policy.restore()['invalidation_reason'])
        self.assertEqual(journal._entry(0).read_bytes(), original)

    def test_compact_prefix_binds_the_full_population_count_and_digest(self):
        prefix = self.candidates[:1]
        journal = checkpoint.CandidateJournal(self.root, self.source, 'b' * 64, prefix,
            population_count=65610, population_sha256='d' * 64)
        journal.restore()
        self.commit(journal, 0)
        resumed = checkpoint.CandidateJournal(self.root, self.source, 'b' * 64, prefix,
            population_count=65610, population_sha256='d' * 64)
        self.assertEqual(resumed.restore()['completed'], 1)
        for count, digest in ((65611, 'd' * 64), (65611, 'e' * 64)):
            changed = checkpoint.CandidateJournal(self.root, self.source, 'b' * 64, prefix,
                population_count=count, population_sha256=digest)
            result = changed.restore()
            self.assertEqual(result['completed'], 0)
            self.assertEqual(result['invalidation_reason'], 'checkpoint_candidates_changed')
        self.assertTrue(journal._entry(0).exists())
        for kwargs in ({'population_count': 0, 'population_sha256': 'd' * 64},
                       {'population_count': 65610}, {'population_sha256': 'd' * 64}):
            with self.assertRaisesRegex(checkpoint.CheckpointError, 'population_binding'):
                checkpoint.CandidateJournal(self.root, self.source, 'b' * 64, prefix, **kwargs)

    def test_return_to_old_binding_does_not_resurrect_previous_results(self):
        journal = self.journal()
        journal.restore()
        self.commit(journal, 0)
        self.source['manifest'] += '#changed'
        self.journal().restore()
        self.source['manifest'] = '#EXTM3U\n'
        restored = self.journal().restore()
        self.assertEqual(restored['completed'], 0)
        self.assertIn('source_changed', restored['invalidation_reason'])
        self.assertTrue(list(journal.storage.glob(journal.identity + '.rejected-*')))

    def test_interrupted_atomic_writer_leaves_only_committed_prefix(self):
        journal = self.journal()
        journal.restore()
        self.commit(journal, 0)
        with patch.object(checkpoint.ads.os, 'rename', side_effect=OSError('simulated process death')):
            with self.assertRaisesRegex(OSError, 'process death'):
                self.commit(journal, 1)
        self.assertFalse(journal._entry(1).exists())
        self.assertEqual(self.journal().restore()['completed'], 1)
        # A process killed after writing a temp file never exposes it as complete.
        (journal.directory / '000001.json.dead.tmp').write_text('{"partial":')
        self.assertEqual(self.journal().restore()['completed'], 1)

    def test_commit_uses_fsync_atomic_json_without_rewriting_previous_candidate(self):
        journal = self.journal()
        journal.restore()
        real = checkpoint.ads.atomic_json
        with patch.object(checkpoint.ads, 'atomic_json', wraps=real) as atomic, \
             patch.object(checkpoint.ads.os, 'fsync', wraps=checkpoint.ads.os.fsync) as fsync:
            self.commit(journal, 0)
            first = journal._entry(0).stat().st_mtime_ns
            self.commit(journal, 1)
        self.assertEqual([call.args[0].name for call in atomic.call_args_list], ['000000.json', '000001.json'])
        self.assertEqual(journal._entry(0).stat().st_mtime_ns, first)
        self.assertEqual(fsync.call_count, 4)
        self.assertTrue(all(call.kwargs['replace'] is False for call in atomic.call_args_list))

    def test_invalid_evidence_and_score_gate_decision_mismatches_are_refused(self):
        mutations = [lambda f, w, r: r.update(score=59),
                     lambda f, w, r: w['candidate_source_gate'].update(valid=False),
                     lambda f, w, r: r.update(decision='unknown'),
                     lambda f, w, r: w.update(decision='unknown'),
                     lambda f, w, r: f.update(ocr=None),
                     lambda f, w, r: r.update(selection='selected'),
                     lambda f, w, r: f['ocr'].update(value=float('nan')),
                     lambda f, w, r: r.update(expected_absence_count=True),
                     lambda f, w, r: r.update(region_indices=[False])]
        journal = self.journal()
        journal.restore()
        for mutate in mutations:
            evidence = self.evidence(0)
            mutate(*evidence)
            with self.assertRaises(checkpoint.CheckpointError):
                journal.commit(0, *evidence, 1)
        for seconds in (-1, float('inf'), True, '1'):
            with self.assertRaises(checkpoint.CheckpointError):
                journal.commit(0, *self.evidence(0), seconds)
        with self.assertRaisesRegex(checkpoint.CheckpointError, 'noncontiguous'):
            self.commit(journal, 1)
        self.assertFalse(journal._entry(0).exists())

    def test_checksum_recomputed_but_row_inconsistent_still_rejected(self):
        journal = self.journal()
        journal.restore()
        self.commit(journal, 0)
        record = json.loads(journal._entry(0).read_text())
        record['payload']['row']['score'] = 99
        record['payload_sha256'] = checkpoint._sha(record['payload'])
        record['record_sha256'] = checkpoint._sha({k: v for k, v in record.items() if k != 'record_sha256'})
        journal._entry(0).write_text(json.dumps(record))
        restored = self.journal().restore()
        self.assertEqual(restored['completed'], 0)
        self.assertIn('row_score_gate_decision_mismatch', restored['invalidation_reason'])

    def test_limits_preserve_evidence_and_report_explicit_error(self):
        journal = self.journal()
        journal.restore()
        evidence = self.evidence(0)
        evidence[0]['ocr']['huge'] = 'x' * 5000
        journal.max_record_bytes = 4000
        with self.assertRaisesRegex(checkpoint.CheckpointError, 'record_byte_limit'):
            journal.commit(0, *evidence, 1)
        self.assertFalse(journal._entry(0).exists())
        journal.max_record_bytes = checkpoint.MAX_RECORD_BYTES
        self.commit(journal, 0)
        raw = journal._entry(0).read_bytes()
        journal.max_total_bytes = journal._usage() + 10
        with self.assertRaisesRegex(checkpoint.CheckpointError, 'total_storage_limit'):
            self.commit(journal, 1)
        self.assertEqual(journal._entry(0).read_bytes(), raw)

    def test_symlink_and_relative_paths_are_refused_without_touching_target(self):
        with self.assertRaisesRegex(checkpoint.CheckpointError, 'absolute_root'):
            checkpoint.CandidateJournal(Path('relative'), self.source, 'b' * 64, [])
        journal = self.journal()
        journal.restore()
        outside = self.root / 'outside'
        outside.write_text('preserve')
        journal._entry(0).symlink_to(outside)
        with self.assertRaisesRegex(checkpoint.CheckpointError, 'unsafe_symlink'):
            self.journal().restore()
        self.assertEqual(outside.read_text(), 'preserve')

    def test_corrupt_header_retains_old_generation_and_starts_empty(self):
        journal = self.journal()
        journal.restore()
        self.commit(journal, 0)
        (journal.directory / 'header.json').write_text('{')
        restored = self.journal().restore()
        self.assertEqual(restored['completed'], 0)
        self.assertEqual(restored['invalidation_reason'], 'checkpoint_header_invalid')
        retained = list(journal.storage.glob(journal.identity + '.rejected-*'))
        self.assertEqual(len(retained), 1)
        self.assertTrue((retained[0] / '000000.json').exists())

    def test_unknown_complete_candidate_preserves_all_observations_and_deep_copies(self):
        journal = self.journal()
        journal.restore()
        evidence = self.evidence(0, valid=False)
        journal.commit(0, *evidence, 2)
        evidence[0]['ocr'].clear()
        restored = self.journal().restore()
        self.assertEqual(restored['records'][0]['payload']['features']['ocr']['candidate']['samples'][0]['text'], 'all original evidence')
        restored['records'][0]['payload']['row']['selection'] = 'selected'
        self.assertNotIn('selection', self.journal().restore()['records'][0]['payload']['row'])


if __name__ == '__main__':
    unittest.main()
