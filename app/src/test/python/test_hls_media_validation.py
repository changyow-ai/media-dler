import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import struct
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
MODULE = Path(__file__).resolve().parents[2] / 'main/assets/hls_media_validation.py'
spec = importlib.util.spec_from_file_location('hls_media_validation', MODULE)
validation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validation)
FFMPEG = shutil.which('ffmpeg')


@unittest.skipUnless(FFMPEG, 'real ffmpeg required')
class MediaValidationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixtures = tempfile.TemporaryDirectory()
        cls.root = Path(cls.fixtures.name)
        cls.ts = cls.root / 'source.ts'
        cls.mp4 = cls.root / 'remux.mp4'
        cls.changed = cls.root / 'changed.ts'
        for path, frequency, size in ((cls.ts, '400', '64x64'), (cls.changed, '900', '80x64')):
            subprocess.run([FFMPEG, '-nostdin', '-v', 'error', '-f', 'lavfi', '-i',
                'testsrc2=size={}:rate=10'.format(size), '-f', 'lavfi', '-i',
                'sine=frequency={}:sample_rate=48000'.format(frequency), '-t', '2',
                '-c:v', 'libx264', '-threads', '2', '-preset', 'ultrafast', '-g', '20',
                '-c:a', 'aac', '-f', 'mpegts', str(path)], check=True, timeout=20)
        subprocess.run([FFMPEG, '-nostdin', '-v', 'error', '-i', str(cls.ts),
                        '-c', 'copy', str(cls.mp4)], check=True, timeout=20)
        cls.data = cls.ts.read_bytes()

    @classmethod
    def tearDownClass(cls):
        cls.fixtures.cleanup()

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.part = Path(self.tmp.name) / 'download.part'
        self.part.write_bytes(self.data)
        self.validator = validation.DownloadValidator(FFMPEG, 'session', 'execution')
        self.addCleanup(self.validator.close)

    def wait_for(self, predicate):
        deadline = time.monotonic() + 5
        while not predicate() and time.monotonic() < deadline:
            time.sleep(0.01)
        self.assertTrue(predicate(), self.validator._error)

    def assert_valid(self, receipt, data=None):
        data = self.data if data is None else data
        self.assertEqual(receipt['status'], 'valid', receipt)
        self.assertTrue(receipt['eof'])
        self.assertTrue(receipt['full_decode'])
        self.assertEqual(receipt['source_bytes'], len(data))
        self.assertEqual(receipt['source_sha256'], hashlib.sha256(data).hexdigest())
        self.assertEqual(receipt['identity'], 'session')
        self.assertEqual(receipt['token'], 'execution')
        self.assertEqual({key: receipt[key] for key in ('validator_sha256', 'ffmpeg_sha256')},
                         validation.proof_identity(FFMPEG))
        json.dumps(receipt)
        self.assertFalse(self.validator._thread.is_alive())
        self.assertTrue(all(not t.is_alive() for t in self.validator._outputs.threads))

    def test_short_clip_defers_until_finish_and_matches_remux(self):
        self.validator.notify(self.part, len(self.data), 2, 2)
        self.assertIsNone(self.validator._outputs)
        receipt = self.validator.finish()
        self.assert_valid(receipt)
        self.assertEqual(receipt['batch_count'], 1)
        self.assertEqual(receipt['first_batch_segments'], 2)
        self.assertEqual(receipt['tail_seconds'], 2)
        self.assertEqual(receipt['fingerprints'], validation.fingerprint_file(self.mp4, FFMPEG))
        self.assertEqual(receipt['fingerprints'], validation.fingerprint_file(self.ts, FFMPEG))
        self.assertIs(receipt, self.validator.finish())

    def test_continuous_context_across_non_independent_packet_cuts(self):
        cuts = [188 * 63, 188 * 141, len(self.data)]
        self.part.write_bytes(self.data[:cuts[0]])
        self.validator.notify(self.part, cuts[0], 12, 0.5)
        self.wait_for(lambda: self.validator._batch_count == 1)
        process = self.validator._outputs.process
        self.assertIsNone(process.poll())
        with self.part.open('ab') as stream:
            stream.write(self.data[cuts[0]:cuts[1]])
            stream.flush()
            os.fsync(stream.fileno())
        self.validator.notify(self.part, cuts[1], 24, 1)
        self.wait_for(lambda: self.validator._batch_count == 2)
        with self.part.open('ab') as stream:
            stream.write(self.data[cuts[1]:])
            stream.flush()
            os.fsync(stream.fileno())
        self.validator.notify(self.part, cuts[2], 25, 2)
        receipt = self.validator.finish()
        self.assert_valid(receipt)
        self.assertEqual(receipt['batch_count'], 3)
        self.assertEqual(receipt['first_batch_segments'], 12)
        self.assertEqual(receipt['tail_seconds'], 1)
        self.assertIs(self.validator._outputs.process, process)
        self.assertEqual(receipt['fingerprints'], validation.fingerprint_file(self.mp4, FFMPEG))

    def test_latest_watermark_covers_resume_prefix_from_zero(self):
        # First notification is resumed fragment 9, never a request to seek past
        # the prefix whose codec state and exact hash are still required.
        self.validator.notify(self.part, len(self.data) // 2, 9, 1)
        self.validator.notify(self.part, len(self.data), 10, 2)
        receipt = self.validator.finish()
        self.assert_valid(receipt)
        self.assertEqual(receipt['segments'], 10)
        self.assertEqual(receipt['first_batch_segments'], 10)

    def test_open_descriptor_survives_native_final_rename(self):
        self.validator.notify(self.part, len(self.data), 1, 2)
        self.part.rename(self.part.with_suffix('.ts'))
        self.assert_valid(self.validator.finish())

    def test_media_seconds_and_byte_thresholds_start_before_finish(self):
        for seconds, byte_threshold in ((30, validation.BATCH_BYTES), (2, len(self.data))):
            with self.subTest(seconds=seconds), patch.object(validation, 'BATCH_BYTES', byte_threshold):
                validator = validation.DownloadValidator(FFMPEG, 'session', 'execution')
                try:
                    validator.notify(self.part, len(self.data), 1, seconds)
                    deadline = time.monotonic() + 5
                    while validator._batch_count != 1 and time.monotonic() < deadline:
                        time.sleep(0.01)
                    self.assertEqual(validator._batch_count, 1, validator._error)
                    self.assertIsNone(validator._outputs.process.poll())
                    self.assertEqual(validator.finish()['status'], 'valid')
                finally:
                    validator.close()

    def test_changed_configuration_and_content_do_not_match(self):
        before = validation.fingerprint_file(self.ts, FFMPEG)
        changed = validation.fingerprint_file(self.changed, FFMPEG)
        self.assertIsNotNone(changed)
        self.assertNotEqual(before['video'], changed['video'])
        self.assertNotEqual(before['audio'], changed['audio'])

    def test_sps_only_colour_metadata_change_stays_bound(self):
        changed = Path(self.tmp.name) / 'colour-metadata.ts'
        subprocess.run([FFMPEG, '-nostdin', '-v', 'error', '-i', str(self.ts),
            '-c', 'copy', '-bsf:v', 'h264_metadata=colour_primaries=1:'
            'transfer_characteristics=1:matrix_coefficients=1', '-f', 'mpegts',
            str(changed)], check=True, timeout=20)

        def coded_slices(path):
            return subprocess.run([FFMPEG, '-nostdin', '-v', 'error', '-i', str(path),
                '-map', '0:v:0', '-c:v', 'copy', '-bsf:v',
                'h264_mp4toannexb,filter_units=pass_types=1-5', '-f', 'h264', 'pipe:1'],
                check=True, capture_output=True, timeout=20).stdout

        # Establish that only parameter metadata changed, not coded pictures.
        original_slices = coded_slices(self.ts)
        self.assertTrue(original_slices)
        self.assertEqual(original_slices, coded_slices(changed))
        original = validation.fingerprint_file(self.ts, FFMPEG)
        altered = validation.fingerprint_file(changed, FFMPEG)
        self.assertIsNotNone(original)
        self.assertIsNotNone(altered)
        self.assertEqual(original['audio'], altered['audio'])
        self.assertNotEqual(original['video'], altered['video'])

    def test_midstream_aac_configuration_cannot_reuse_final_proof(self):
        root = Path(self.tmp.name)
        blocks = []
        for index, (rate, channels) in enumerate(((48000, 1), (44100, 2))):
            path = root / 'block-{}.ts'.format(index)
            subprocess.run([FFMPEG, '-nostdin', '-v', 'error', '-f', 'lavfi', '-i',
                'testsrc2=size=64x64:rate=10', '-f', 'lavfi', '-i',
                'sine=frequency=400:sample_rate={}'.format(rate), '-t', '2',
                '-c:v', 'libx264', '-threads', '2', '-preset', 'ultrafast', '-g', '20',
                '-c:a', 'aac', '-ac', str(channels), '-mpegts_flags', '+initial_discontinuity',
                '-output_ts_offset', str(index * 2.2), '-f', 'mpegts', str(path)],
                check=True, timeout=20)
            blocks.append(path.read_bytes())
        # Explicit transport discontinuities and increasing timestamps avoid a
        # spurious continuity-counter error masking the codec change under test.
        joined = b''.join(blocks)
        self.part.write_bytes(joined)
        final = root / 'configuration-change.mp4'
        subprocess.run([FFMPEG, '-nostdin', '-v', 'error', '-i', str(self.part),
            '-c', 'copy', str(final)], check=True, timeout=20)

        def adts_frames(path):
            data = subprocess.run([FFMPEG, '-nostdin', '-v', 'error', '-i', str(path),
                '-map', '0:a:0', '-c:a', 'copy', '-f', 'adts', 'pipe:1'],
                check=True, capture_output=True, timeout=20).stdout
            configurations, payloads = set(), []
            offset = 0
            while offset < len(data):
                header = data[offset:offset + 7]
                self.assertEqual(header[:1], b'\xff')
                self.assertEqual(header[1] & 0xf6, 0xf0)
                size = ((header[3] & 3) << 11) | (header[4] << 3) | (header[5] >> 5)
                header_size = 7 if header[1] & 1 else 9
                self.assertGreaterEqual(size, header_size)
                self.assertLessEqual(offset + size, len(data))
                configurations.add(((header[2] >> 2) & 15,
                                    ((header[2] & 1) << 2) | (header[3] >> 6)))
                payloads.append(data[offset + header_size:offset + size])
                offset += size
            return configurations, payloads

        source_config, source_payloads = adts_frames(self.part)
        final_config, final_payloads = adts_frames(final)
        self.assertEqual(source_config, {(3, 1), (4, 2)})
        self.assertEqual(len(final_config), 1)
        self.assertEqual(source_payloads, final_payloads)
        source_fingerprints = validation.fingerprint_file(self.part, FFMPEG)
        final_fingerprints = validation.fingerprint_file(final, FFMPEG)
        self.assertIsNotNone(source_fingerprints)
        self.assertIsNotNone(final_fingerprints)
        self.assertEqual(source_fingerprints['video'], final_fingerprints['video'])
        # Identical encoded AAC frames must still differ when MP4 replaced the
        # changing ADTS headers with its single initial configuration.
        self.assertNotEqual(source_fingerprints['audio'], final_fingerprints['audio'])
        self.validator.notify(self.part, len(joined), 2, 4)
        receipt = self.validator.finish()
        self.assertTrue(receipt['status'] == 'unavailable'
                        or receipt['fingerprints'] != final_fingerprints, receipt)

    def test_bframe_ctts_only_mutation_changes_timing_proof(self):
        root = Path(self.tmp.name)
        source, final, changed = (root / name for name in ('bframes.ts', 'bframes.mp4', 'bad-ctts.mp4'))
        subprocess.run([FFMPEG, '-nostdin', '-v', 'error', '-f', 'lavfi', '-i',
            'testsrc2=size=64x64:rate=25', '-f', 'lavfi', '-i',
            'sine=frequency=400:sample_rate=48000', '-t', '4', '-c:v', 'libx264',
            '-threads', '2', '-preset', 'medium', '-bf', '3', '-g', '100',
            '-c:a', 'aac', '-f', 'mpegts', str(source)], check=True, timeout=20)
        subprocess.run([FFMPEG, '-nostdin', '-v', 'error', '-i', str(source),
            '-c', 'copy', str(final)], check=True, timeout=20)
        original = final.read_bytes()
        mutated = bytearray(original)

        def find_ctts(start, end):
            while start < end:
                size, kind = struct.unpack_from('>I4s', mutated, start)
                header = 8
                if size == 1:
                    size = struct.unpack_from('>Q', mutated, start + 8)[0]
                    header = 16
                elif size == 0:
                    size = end - start
                self.assertGreaterEqual(size, header)
                self.assertLessEqual(start + size, end)
                if kind == b'ctts':
                    return start + header
                if kind in (b'moov', b'trak', b'mdia', b'minf', b'stbl'):
                    found = find_ctts(start + header, start + size)
                    if found is not None:
                        return found
                start += size
            return None

        ctts = find_ctts(0, len(mutated))
        self.assertIsNotNone(ctts)
        entries = struct.unpack_from('>I', mutated, ctts + 4)[0]
        self.assertGreater(entries, 0)
        last_offset = ctts + 8 + (entries - 1) * 8 + 4
        self.assertNotEqual(mutated[last_offset:last_offset + 4], b'\0' * 4)
        mutated[last_offset:last_offset + 4] = b'\0' * 4
        changed.write_bytes(mutated)
        self.assertEqual(original[:last_offset], mutated[:last_offset])
        self.assertEqual(original[last_offset + 4:], mutated[last_offset + 4:])

        self.validator.notify(source, source.stat().st_size, 1, 4)
        receipt = self.validator.finish()
        self.assertEqual(receipt['status'], 'valid', receipt)
        before = validation.fingerprint_file(final, FFMPEG)
        after = validation.fingerprint_file(changed, FFMPEG)
        self.assertIsNotNone(before)
        self.assertIsNotNone(after)
        self.assertEqual(receipt['fingerprints'], before)
        self.assertEqual(before['video'], after['video'])
        self.assertEqual(before['audio'], after['audio'])
        self.assertEqual(before['video_timing']['packets'], 100)
        self.assertEqual(before['video_timing']['packets'], after['video_timing']['packets'])
        self.assertNotEqual(before['video_timing'], after['video_timing'])
        decoded = subprocess.run([FFMPEG, '-nostdin', '-v', 'error', '-xerror',
            '-i', str(changed), '-map', '0:v:0', '-vsync', '0', '-map', '0:a:0',
            '-af', 'asetpts=N/SR/TB', '-f', 'null', '-'], capture_output=True, timeout=20)
        self.assertTrue(decoded.returncode or decoded.stderr)

    def test_corrupted_h264_payload_fails_decode(self):
        # Preserve TS packet/PES headers but break the first IDR NAL payload.
        corrupted = bytearray(self.data)
        marker = corrupted.find(b'\x00\x00\x01\x65')
        self.assertGreater(marker, 0)
        corrupted[marker + 4:marker + 104] = b'\xff' * 100
        self.part.write_bytes(corrupted)
        self.validator.notify(self.part, len(corrupted), 1, 2)
        receipt = self.validator.finish()
        self.assertEqual(receipt['status'], 'unavailable', receipt)
        self.assertFalse(receipt['full_decode'])
        self.assertFalse(receipt['eof'])
        self.assertIn('ffmpeg_validation_failed', receipt['reason'])

    def test_invalid_or_truncated_source_never_produces_proof(self):
        self.part.write_bytes(b'not transport stream')
        self.validator.notify(self.part, 4000, 1, 2)
        receipt = self.validator.finish()
        self.assertEqual(receipt['status'], 'unavailable')
        self.assertIn('committed_prefix_truncated', receipt['reason'])
        self.assertIsNone(validation.fingerprint_file(self.part, FFMPEG))

    def test_close_kills_child_and_joins_all_threads(self):
        self.validator.notify(self.part, 188 * 63, 12, 0.5)
        self.wait_for(lambda: self.validator._batch_count == 1)
        outputs = self.validator._outputs
        self.validator.close()
        self.assertIsNotNone(outputs.process.poll())
        self.assertFalse(self.validator._thread.is_alive())
        self.assertTrue(all(not thread.is_alive() for thread in outputs.threads))
        self.assertEqual(self.validator.finish()['status'], 'unavailable')
        self.validator.close()

    def test_stderr_even_with_exit_zero_is_unavailable_and_bounded(self):
        wrapper = Path(self.tmp.name) / 'noisy-ffmpeg'
        wrapper.write_text('#!' + sys.executable + '\nimport os, sys\n'
            + "os.write(2, b'x' * 20000)\n"
            + 'os.execv(' + repr(FFMPEG) + ', [' + repr(FFMPEG) + '] + sys.argv[1:])\n')
        wrapper.chmod(0o755)
        validator = validation.DownloadValidator(str(wrapper), 'session', 'execution')
        try:
            validator.notify(self.part, len(self.data), 1, 2)
            receipt = validator.finish()
            self.assertEqual(validator._outputs.process.returncode, 0)
            self.assertEqual(receipt['status'], 'unavailable')
            self.assertLessEqual(len(receipt['reason']), validation.DIAGNOSTIC_BYTES)
            self.assertEqual(len(validator._outputs.diagnostics), validation.DIAGNOSTIC_BYTES)
            self.assertIsNone(validation.fingerprint_file(self.mp4, str(wrapper)))
        finally:
            validator.close()

    def test_exit_timeout_kills_child_and_joins_readers(self):
        wrapper = Path(self.tmp.name) / 'stalled-ffmpeg'
        wrapper.write_text('#!' + sys.executable + '\nimport time\ntime.sleep(30)\n')
        wrapper.chmod(0o755)
        validator = validation.DownloadValidator(str(wrapper), 'session', 'execution')
        try:
            validator.notify(self.part, 188, 1, 0.1)
            with patch.object(validation, 'FINISH_TIMEOUT', 0.1):
                receipt = validator.finish()
            self.assertEqual(receipt['status'], 'unavailable')
            self.assertIsNotNone(validator._outputs.process.poll())
            self.assertTrue(all(not t.is_alive() for t in validator._outputs.threads))
            self.assertFalse(validator._thread.is_alive())
        finally:
            validator.close()

    def test_empty_audio_only_and_missing_executable_fail_closed(self):
        self.assertEqual(self.validator.finish()['status'], 'unavailable')
        audio = validation.DownloadValidator(FFMPEG, 'session', 'token', audio_only=True)
        audio.notify(self.part, len(self.data), 1, 2)
        self.assertEqual(audio.finish()['reason'], 'audio_only_unsupported')
        self.assertIsNone(validation.fingerprint_file(self.ts, FFMPEG, audio_only=True))
        missing = validation.DownloadValidator('/missing/ffmpeg', 'session', 'token')
        missing.notify(self.part, len(self.data), 1, 2)
        self.assertEqual(missing.finish()['status'], 'unavailable')
        self.assertIsNone(validation.fingerprint_file(self.ts, '/missing/ffmpeg'))


if __name__ == '__main__':
    unittest.main()
