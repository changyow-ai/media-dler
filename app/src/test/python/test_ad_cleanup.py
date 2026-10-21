import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
from contextlib import contextmanager
from fractions import Fraction

sys.dont_write_bytecode = True
MODULE = Path(__file__).resolve().parents[2] / 'main/assets/ad_cleanup.py'
spec = importlib.util.spec_from_file_location('ad_cleanup', MODULE)
ad = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ad)


def signature(payload, offset=11):
    return {'id': 'test-verified-ad', 'length': len(payload),
            'sha256': hashlib.sha256(payload).hexdigest(), 'anchor_offset': offset,
            'anchor_hex': payload[offset:offset + 64].hex()}


def metadata():
    return {'streams': [
        {'index': 0, 'codec_type': 'video', 'codec_name': 'h264', 'time_base': '1/1000'},
        {'index': 1, 'codec_type': 'audio', 'codec_name': 'aac', 'time_base': '1/1000'}],
        'format': {'format_name': 'mov,mp4', 'duration': '10'}}


def packet(pos, pts, index=0, keyframe=True, digest=b'x' * 32):
    return {'stream_index': index, 'pos': pos, 'size': 10, 'pts': pts, 'dts': pts,
            'duration': 100, 'keyframe': keyframe, 'hash': digest}


class CleanupTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.source = self.root / 'source.mp4'
        self.output = self.root / 'cleaned.mp4'
        self.result = self.root / 'result.json'
        self.catalog = self.root / 'catalog.json'
        self.ffmpeg, self.ffprobe = self.root / 'ffmpeg', self.root / 'ffprobe'
        self.payload = bytes(range(128))
        self.sig = signature(self.payload)
        self.source.write_bytes(b'before' + self.payload + b'after')
        self.catalog.write_text(json.dumps({'schema': 1, 'signatures': [self.sig]}))

    def cleanup(self):
        return ad.cleanup(self.source, self.output, self.result, self.catalog,
                          self.ffmpeg, self.ffprobe)

    def test_full_hash_rejects_identical_anchor_with_changed_tail(self):
        changed = self.payload[:-1] + b'X'
        self.source.write_bytes(changed)
        self.assertEqual(ad.find_matches(self.source, [self.sig]), [])

    def test_repeated_varied_positions_and_chunk_boundary(self):
        # Anchor straddles a scan chunk boundary and the second match moves freely.
        data = b'prefix!' + self.payload + b'varying-padding' + self.payload + b'end'
        self.source.write_bytes(data)
        with patch.object(ad, 'CHUNK_SIZE', 32):
            self.assertEqual(ad.find_matches(self.source, [self.sig]), [
                {'start': 7, 'end': 135}, {'start': 150, 'end': 278}])

    def test_no_match_does_not_launch_external_tools_or_write_output(self):
        self.source.write_bytes(b'unknown content')
        with patch.object(ad.subprocess, 'run', side_effect=AssertionError('external tool')), \
                patch('builtins.print') as progress:
            result = self.cleanup()
        progress.assert_called_once_with('AD_CLEANUP_STAGE scan', flush=True)
        self.assertEqual(result, {'schema': 1, 'status': 'unchanged',
                                 'removed_ads': 0, 'removed_seconds': 0})
        self.assertEqual(json.loads(self.result.read_text()), result)
        self.assertFalse(self.output.exists())
        self.assertEqual(self.source.read_bytes(), b'unknown content')

    def test_candidate_limit_bounds_work(self):
        self.source.write_bytes(b'a' * 1000)
        sig = signature(b'a' * 128)
        with patch.object(ad, 'MAX_CANDIDATES', 3), self.assertRaises(ad.CleanupError):
            ad.find_matches(self.source, [sig])

    def test_overlapping_full_matches_are_rejected(self):
        self.source.write_bytes(b'a' * 130)
        with self.assertRaisesRegex(ad.CleanupError, 'Overlapping'):
            ad.find_matches(self.source, [signature(b'a' * 128)])

    def test_existing_output_result_and_dangling_symlinks_are_preserved(self):
        for path in (self.output, self.result):
            with self.subTest(path=path):
                path.write_bytes(b'keep')
                with self.assertRaises(ad.CleanupError):
                    self.cleanup()
                self.assertEqual(path.read_bytes(), b'keep')
                path.unlink()
        self.output.symlink_to(self.root / 'missing')
        with self.assertRaises(ad.CleanupError):
            self.cleanup()
        self.assertTrue(self.output.is_symlink())

    def test_source_as_output_is_rejected_without_changes(self):
        before = self.source.read_bytes()
        with self.assertRaises(ad.CleanupError):
            ad.cleanup(self.source, self.source, self.result, self.catalog, self.ffmpeg, self.ffprobe)
        self.assertEqual(self.source.read_bytes(), before)

    def test_metadata_rejects_extra_or_unsupported_streams_and_bad_timebase(self):
        cases = []
        extra = metadata()
        extra['streams'].append({'index': 2, 'codec_type': 'subtitle'})
        cases.append(extra)
        codec = metadata()
        codec['streams'][0]['codec_name'] = 'hevc'
        cases.append(codec)
        timebase = metadata()
        timebase['streams'][0]['time_base'] = '0/0'
        cases.append(timebase)
        container = metadata()
        container['format']['format_name'] = 'matroska'
        cases.append(container)
        for data in cases:
            with self.subTest(data=data), self.assertRaises(ad.CleanupError):
                ad.validate_metadata(data)

    def check_boundaries(self, records):
        streams, _ = ad.validate_metadata(metadata())
        self.source.write_bytes(b'_' * 1000)
        @contextmanager
        def fake_packets(*args):
            yield iter(records)
        matches = [{'start': 100, 'end': 200}]
        with patch.object(ad, 'packets', fake_packets):
            ad.discover_boundaries(self.ffprobe, self.source, streams, matches)
        return matches

    def test_boundaries_require_present_keyframes_valid_packets_and_ordered_timestamps(self):
        valid = [packet(10, 0, index=1), packet(100, 1000), packet(200, 3000)]
        self.assertEqual(self.check_boundaries(valid)[0]['seconds'], Fraction(2))
        invalid = [valid[:-1],
                   [valid[0], packet(100, 1000, keyframe=False), valid[2]],
                   [valid[0], valid[1], packet(200, 900)],
                   [packet(95, 0, index=1), valid[1], valid[2]],
                   [valid[0], valid[1], packet(2000, 3000)]]
        for records in invalid:
            with self.subTest(records=records), self.assertRaises(ad.CleanupError):
                self.check_boundaries(records)

    def test_filter_uses_verified_byte_positions_and_current_container_duration(self):
        expression = ad.filter_expression([{'start': 123, 'end': 456, 'seconds': Fraction(7, 3)}])
        self.assertEqual(expression, "noise=amount=0:drop='gte(pos,123)*lt(pos,456)',"
                         "setts=pts='PTS-gte(POS,456)*(7/3)/TB':dts='DTS-gte(POS,456)*(7/3)/TB'")

    def test_failed_validation_removes_only_owned_partial_and_preserves_source(self):
        before = self.source.read_bytes()
        unrelated = self.root / 'unrelated.mp4'
        unrelated.write_bytes(b'keep')
        def boundaries(ffprobe, source, streams, matches):
            matches[0]['seconds'] = Fraction(2)
        def remux(ffmpeg, source, target, streams, matches):
            target.write_bytes(b'invalid output')
        with patch.object(ad, 'run_metadata', return_value=metadata()), \
                patch.object(ad, 'discover_boundaries', side_effect=boundaries), \
                patch.object(ad, 'remux', side_effect=remux), \
                patch.object(ad, 'validate_output', side_effect=ad.CleanupError('validation failed')):
            with self.assertRaisesRegex(ad.CleanupError, 'validation failed'):
                self.cleanup()
        self.assertEqual(self.source.read_bytes(), before)
        self.assertEqual(unrelated.read_bytes(), b'keep')
        self.assertFalse(self.output.exists())
        self.assertFalse(self.result.exists())
        self.assertEqual(list(self.root.glob('.ad-*')), [])

    def test_result_publication_failure_rolls_back_own_output(self):
        def boundaries(ffprobe, source, streams, matches):
            matches[0]['seconds'] = Fraction(2)
        def remux(ffmpeg, source, target, streams, matches):
            target.write_bytes(b'validated')
        with patch.object(ad, 'run_metadata', return_value=metadata()), \
                patch.object(ad, 'discover_boundaries', side_effect=boundaries), \
                patch.object(ad, 'remux', side_effect=remux), \
                patch.object(ad, 'validate_output'), \
                patch.object(ad, 'atomic_result', side_effect=OSError('disk failure')):
            with self.assertRaises(OSError):
                self.cleanup()
        self.assertFalse(self.output.exists())
        self.assertFalse(self.result.exists())
        self.assertEqual(list(self.root.glob('.ad-*')), [])

    def test_output_created_during_validation_is_preserved(self):
        def boundaries(ffprobe, source, streams, matches):
            matches[0]['seconds'] = Fraction(2)
        def remux(ffmpeg, source, target, streams, matches):
            target.write_bytes(b'validated')
        with patch.object(ad, 'run_metadata', return_value=metadata()), \
                patch.object(ad, 'discover_boundaries', side_effect=boundaries), \
                patch.object(ad, 'remux', side_effect=remux), \
                patch.object(ad, 'validate_output', side_effect=lambda *args: self.output.write_bytes(b'keep')):
            with self.assertRaisesRegex(ad.CleanupError, 'overwrite'):
                self.cleanup()
        self.assertEqual(self.output.read_bytes(), b'keep')
        self.assertFalse(self.result.exists())
        self.assertEqual(list(self.root.glob('.ad-*')), [])

    def test_atomic_result_preserves_existing_file_without_hardlink_support(self):
        self.result.write_bytes(b'keep')
        with patch.object(ad.os, 'link', side_effect=AssertionError('unavailable'), create=True):
            with self.assertRaisesRegex(ad.CleanupError, 'overwrite'):
                ad.atomic_result(self.result, {'schema': 1})
        self.assertEqual(self.result.read_bytes(), b'keep')
        self.assertEqual(list(self.root.glob('.ad-*')), [])

    def test_cleaned_result_is_written_only_after_validation(self):
        def boundaries(ffprobe, source, streams, matches):
            matches[0]['seconds'] = Fraction(7, 3)
        def remux(ffmpeg, source, target, streams, matches):
            target.write_bytes(b'validated')
        def validate(*args):
            self.assertFalse(self.output.exists())
            self.assertFalse(self.result.exists())
            self.assertEqual(progress.call_args.args, ('AD_CLEANUP_STAGE validate',))
        with patch.object(ad, 'run_metadata', return_value=metadata()), \
                patch.object(ad, 'discover_boundaries', side_effect=boundaries), \
                patch.object(ad, 'remux', side_effect=remux), \
                patch.object(ad, 'validate_output', side_effect=validate), \
                patch('builtins.print') as progress:
            data = self.cleanup()
        self.assertEqual([(call.args, call.kwargs) for call in progress.call_args_list], [
            (('AD_CLEANUP_STAGE ' + stage,), {'flush': True})
            for stage in ('scan', 'probe', 'remux', 'validate')])
        self.assertEqual(data['status'], 'cleaned')
        self.assertEqual(data['removed_ads'], 1)
        self.assertAlmostEqual(data['removed_seconds'], 7 / 3)
        self.assertEqual(data['output_size'], 9)
        self.assertEqual(self.output.read_bytes(), b'validated')
        self.assertEqual(json.loads(self.result.read_text()), data)

    def test_output_validation_rejects_payload_timestamps_counts_and_duration(self):
        streams, _ = ad.validate_metadata(metadata())
        self.output.write_bytes(b'_' * 1000)
        source_packets = [packet(10, 0), packet(100, 1000), packet(200, 3000)]
        audio = [packet(20, 0, index=1), packet(110, 1000, index=1), packet(210, 3000, index=1)]
        clean = [packet(10, 0), packet(30, 1000)]
        matches = [{'start': 100, 'end': 200, 'seconds': Fraction(2)}]
        meta = metadata()
        meta['format']['duration'] = '1.1'
        variants = [[clean[0], dict(clean[1], hash=b'y' * 32)],
                    [clean[0], dict(clean[1], pts=1010)], clean[:1],
                    [clean[0], dict(clean[1], dts=-1)]]
        for cleaned in variants:
            @contextmanager
            def fake_packets(ffprobe, path, index=None):
                records = source_packets if index == 0 else audio
                if path == self.output:
                    records = cleaned if index == 0 else [dict(p, stream_index=1) for p in clean]
                yield iter(records)
            with self.subTest(cleaned=cleaned), \
                    patch.object(ad, 'run_metadata', return_value=meta), \
                    patch.object(ad, 'packets', fake_packets), self.assertRaises(ad.CleanupError):
                ad.validate_output(self.ffprobe, self.source, self.output, streams,
                                   Fraction('3.1'), matches)
        meta['format']['duration'] = '5'
        with patch.object(ad, 'run_metadata', return_value=meta), self.assertRaises(ad.CleanupError):
            ad.validate_output(self.ffprobe, self.source, self.output, streams, Fraction('3.1'), matches)


if __name__ == '__main__':
    unittest.main()
