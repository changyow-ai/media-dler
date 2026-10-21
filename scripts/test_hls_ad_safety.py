#!/usr/bin/env python3
"""Feature-only safety baseline; expected failures remain visible until fixed."""
import copy
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import check_hls_ad_safety as safety


class SafetyBaselineTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = json.loads(safety.DEFAULT_FIXTURE.read_text())
        cls.report = safety.run()
        cls.rows = {row['id']: row for row in cls.report['synthetic_cases']}

    def test_fixed_scenarios_cover_requested_negative_categories(self):
        negatives = [case for case in self.fixture['synthetic_cases'] if case['truth'] != 'ad']
        categories = {category for case in negatives for category in case['categories']}
        self.assertTrue({'intro', 'outro', 'credits', 'large_card', 'station_logo',
                         'promo_overlay', 'volume_jump', 'discontinuity', 'hard_cut', 'ABA'} <= categories)
        for case in negatives:
            self.assertIn('synthetic', case['provenance'])
            self.assertEqual(case['coverage_seconds'], [0, 10])
            self.assertIn('Not inferred from scorer output', case['label_evidence'])

    def test_baseline_lists_actual_known_failures_instead_of_claiming_clean(self):
        self.assertEqual(self.report['synthetic_false_positives'], self.fixture['known_synthetic_safety_failures'])
        self.assertTrue(self.report['acceptance'].startswith('incomplete_'))

    def test_isolated_normal_cues_are_preserved(self):
        known_failures = set(self.fixture['known_synthetic_safety_failures'])
        for row in self.rows.values():
            if row['truth'] != 'ad' and row['id'] not in known_failures:
                with self.subTest(case=row['id']):
                    self.assertFalse(row['weighted']['selected_for_removal'])

    @unittest.expectedFailure
    def test_normal_aba_with_gain_and_overlay_must_be_preserved(self):
        self.assertFalse(self.rows['synthetic-normal-aba-loud-overlay']['weighted']['selected_for_removal'])

    @unittest.expectedFailure
    def test_normal_large_title_card_at_hard_cut_must_be_preserved(self):
        self.assertFalse(self.rows['synthetic-large-title-card-hardcut']['weighted']['selected_for_removal'])

    def test_full_ad_positive_prevents_all_preserved_from_passing(self):
        self.assertEqual(self.rows['synthetic-full-ad']['weighted']['outcome'], 'full_hit')
        self.assertEqual(self.report['synthetic_ad_and_neighbors']['selected_segment_indices'], [1])

    def test_invalid_source_is_unknown_even_with_strong_features(self):
        row = self.rows['synthetic-unverified-full-ad']['weighted']
        self.assertGreaterEqual(row['score'], self.fixture['threshold'])
        self.assertEqual(row['outcome'], 'unknown')
        self.assertFalse(row['selected_for_removal'])

    def test_archived_scores_never_become_fresh_results_or_independent_truth(self):
        self.assertTrue(self.report['archived_cases'])
        for row in self.report['archived_cases']:
            self.assertIn('ground_truth', row['annotations'])
            self.assertIn('archived_observation_only', row)
            self.assertEqual(row['weighted']['outcome'], 'unknown')
            self.assertEqual(row['fast']['outcome'], 'unknown')
            self.assertEqual(row['produced_media_validation'], 'not_run')
        for row in self.rows.values():
            self.assertEqual(row['fast']['outcome'], 'unknown')

    def test_strict_runner_returns_nonzero_for_selected_negatives(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / 'report.json'
            with patch('builtins.print'):
                self.assertEqual(safety.main(['--report', str(output)]), 1)
            self.assertEqual(json.loads(output.read_text())['synthetic_false_positives'],
                             self.fixture['known_synthetic_safety_failures'])

    def test_policy_change_requires_explicit_fixture_versioning(self):
        fixture = copy.deepcopy(self.fixture)
        fixture['scorer_sha256'] = '0' * 64
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'changed-fixture.json'
            path.write_text(json.dumps(fixture))
            with self.assertRaisesRegex(ValueError, 'Frozen baseline differs'):
                safety.run(path)


if __name__ == '__main__':
    unittest.main()
