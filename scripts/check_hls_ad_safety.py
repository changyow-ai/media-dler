#!/usr/bin/env python3
"""Replay fixed synthetic safety vectors; audit, never reconstruct, archived truth.

Exit 1 means a negative feature vector would be selected for removal. Real-media
coverage can remain unknown even with exit 0; inspect the report's acceptance.
This performs no network, media generation, OCR, device access, or source edits.
"""
import argparse
import hashlib
import importlib.util
import json
import sys
from pathlib import Path

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
DEFAULT_FIXTURE = ROOT / 'scripts/fixtures/ad-safety-v1.json'
sys.path.insert(0, str(ROOT / 'app/src/main/assets'))
import weighted_hls_ads as runtime


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_scorer():
    path = ROOT / 'scripts/score-hls-ad-features.py'
    spec = importlib.util.spec_from_file_location('safety_scorer', path)
    scorer = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(scorer)
    return scorer


def synthetic_features(case):
    """Authored feature values, not measured media or reconstructed old scores."""
    values = case['features']
    inputs = {role: {'status': 'ok', 'audio': {'rms_pcm16': 100, 'peak_pcm16': 200},
                     'visual': {'native_adjacent_mae_p90': .01}}
              for role in ('before', 'candidate', 'after')}
    inputs['candidate']['audio'] = {
        'rms_pcm16': 100 * 10 ** (values['gain_db'] / 20), 'peak_pcm16': 1000}
    scene = {'continuous': (0, 0, 0), 'hard_cut': (.2, .2, .2), 'ABA': (0, .2, .2)}
    joined, start, end = scene[values['scene']]
    observations = []
    if values['text']:
        observations = [{'text': values['text'], 'confidence': 1, 'box': values['box']}]
    return {'id': case['id'], 'candidate_gate': 'valid' if values.get('valid', True) else 'unknown',
            'inputs': inputs,
            'bridge_only_S': {'joined_edge_mae': joined, 'start_seam_mae': start, 'end_seam_mae': end},
            'ocr': {'candidate': {'samples': [{'time_seconds': t, 'observations': observations}
                                             for t in (1, 5, 9)]}}}


def evaluate_synthetic(case, scorer):
    scored = scorer.score_case(synthetic_features(case))
    row = dict(scored, region_indices=[0], segment_indices=[0], duration_seconds=10)
    selected = bool(runtime.select_groups([row]))
    valid = scored['candidate_source_gate']['valid']
    outcome = ('false_positive' if selected else 'preserved') if case['truth'] != 'ad' else (
        'unknown' if not valid else 'full_hit' if selected else 'missed')
    return {'id': case['id'], 'provenance': case['provenance'], 'truth': case['truth'],
            'categories': case['categories'], 'coverage_seconds': case['coverage_seconds'],
            'weighted': {'score': scored['score'], 'decision': scored['decision'],
                         'selected_for_removal': selected, 'outcome': outcome,
                         'families': {k: v['points'] for k, v in scored['families'].items()},
                         'soft_negative': scored['soft_negative']},
            'fast': {'outcome': 'unknown', 'reason': 'feature_vector_has_no_encoded_packet_fingerprint'},
            'produced_media_validation': 'not_run_no_media_in_feature_fixture'}


def audit_archived(reference, archived_results):
    path = ROOT / reference['path']
    if sha256(path) != reference['sha256']:
        raise ValueError('Archived definitions changed: ' + reference['path'])
    definition = next(c for c in json.loads(path.read_text())['cases'] if c['id'] == reference['id'])
    observations = []
    for role in ('before', 'candidate', 'after'):
        source = definition.get(role)
        if source:
            observations.append({'role': role, **source, 'exists': Path(source['path']).is_file()})
    prior = next(c for c in archived_results['cases'] if c['id'] == reference['id'])
    return {'id': reference['id'], 'definition': reference, 'annotations': definition['annotations'],
            'boundaries': definition['boundaries'], 'source_files': observations,
            'fast': {'outcome': 'unknown', 'reason': 'packet_fingerprint_not_rerun'},
            'weighted': {'outcome': 'unknown', 'reason': 'raw_features_not_present_in_committed_results'},
            'archived_observation_only': {'baseline_exact_flag': prior['baseline_exact_flag'],
                                          'weighted_v3_score': prior['score_v3'],
                                          'weighted_v3_flag': prior['v3_flag'],
                                          'action': prior['action']},
            'produced_media_validation': 'not_run'}


def run(fixture_path=DEFAULT_FIXTURE):
    fixture = json.loads(fixture_path.read_text())
    scorer = load_scorer()
    frozen = sha256(ROOT / 'scripts/score-hls-ad-features.py') == fixture['scorer_sha256']
    catalog_frozen = sha256(ROOT / 'app/src/main/assets/hls_ad_fingerprints.json') == fixture['catalog_sha256']
    if not frozen or not catalog_frozen or scorer.SETTINGS['threshold'] != fixture['threshold']:
        raise ValueError('Frozen baseline differs; version the fixture before comparing a new policy')
    result_path = ROOT / fixture['archived_results']['path']
    if sha256(result_path) != fixture['archived_results']['sha256']:
        raise ValueError('Archived result provenance changed')
    archived = json.loads(result_path.read_text())
    synthetic = [evaluate_synthetic(c, scorer) for c in fixture['synthetic_cases']]
    # Test both the positive and its two adjacent normal regions together.
    triplet_ids = ['synthetic-ad-before-normal', 'synthetic-full-ad', 'synthetic-ad-after-normal']
    triplet_rows = []
    for index, case_id in enumerate(triplet_ids):
        case = next(c for c in fixture['synthetic_cases'] if c['id'] == case_id)
        triplet_rows.append(dict(scorer.score_case(synthetic_features(case)),
                                region_indices=[index], segment_indices=[index], duration_seconds=10))
    groups = runtime.select_groups(triplet_rows)
    false_positives = [c['id'] for c in synthetic if c['weighted']['outcome'] == 'false_positive']
    return {'schema': 1, 'fixture_sha256': sha256(fixture_path), 'baseline_commit': fixture['baseline_commit'],
            'scorer_sha256': fixture['scorer_sha256'], 'catalog_sha256': fixture['catalog_sha256'],
            'runtime_sha256': sha256(ROOT / 'app/src/main/assets/weighted_hls_ads.py'),
            'runner_sha256': sha256(Path(__file__)),
            'policy': scorer.POLICY, 'settings': scorer.SETTINGS,
            'synthetic_cases': synthetic, 'synthetic_false_positives': false_positives,
            'synthetic_ad_and_neighbors': {'ordered_cases': triplet_ids,
                                         'selected_segment_indices': [s for g in groups for s in g['segment_indices']]},
            'archived_cases': [audit_archived(ref, archived) for ref in fixture['archived_case_references']],
            'real_coverage_gaps': fixture['real_coverage_gaps'],
            'acceptance': 'incomplete_real_media_replay_output_validation_and_independent_android_required',
            'accuracy_claim': 'none_synthetic_feature_checks_are_not_real_media_detection_accuracy'}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture', type=Path, default=DEFAULT_FIXTURE)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args(argv)
    report = run(args.fixture)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'synthetic_false_positives': report['synthetic_false_positives'],
                      'acceptance': report['acceptance'], 'report': str(args.report)}))
    return 1 if report['synthetic_false_positives'] else 0


if __name__ == '__main__':
    raise SystemExit(main())
