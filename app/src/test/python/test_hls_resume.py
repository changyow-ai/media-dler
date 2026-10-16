import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
import sys
from unittest.mock import patch

# Import the production asset without adding host bytecode to the APK assets.
sys.dont_write_bytecode = True
MODULE = Path(__file__).resolve().parents[2] / 'main/assets/hls_resume.py'
spec = importlib.util.spec_from_file_location('hls_resume', MODULE)
hls_resume = importlib.util.module_from_spec(spec)
spec.loader.exec_module(hls_resume)

MANIFEST = '#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXTINF:5,\na.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:5,\n../b.ts\n#EXT-X-ENDLIST\n'


class CheckpointTests(unittest.TestCase):
    def test_only_exercised_engine_versions_can_enable_recovery(self):
        for version in ('2025.11.12', '2026.08.19'):
            hls_resume.check_engine_version(version)
        with self.assertRaises(hls_resume.ResumeError):
            hls_resume.check_engine_version('2099.01.01')

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.resume = self.root / '.resume'
        self.resume.mkdir()
        self.spec = self.resume / 'session.json'
        self.spec.write_text(json.dumps({'schema': 1, 'identity': 'a' * 64,
            'url': 'https://example.test/child/list.m3u8', 'manifest': MANIFEST}))
        self.output = self.root / 'media.mp4'
        self.part = Path(str(self.output) + '.part')
        self.journal = Path(str(self.output) + '.ytdl')
        self.store = hls_resume.CheckpointStore(self.spec)

    def checkpoint(self, index=2, start=3, end=6, identity=None):
        data = {'schema': 1, 'identity': identity or 'a' * 64, 'filename': self.output.name,
                'index': index, 'start_offset': start, 'end_offset': end}
        self.store.path.write_text(json.dumps(data))
        return self.store.path.read_bytes()

    def test_truncated_tail_and_corrupt_journal_recover_from_sidecar(self):
        self.part.write_bytes(b'AAABBBbroken')
        checkpoint = self.checkpoint()
        self.journal.write_text('corrupt')
        owned = Path(str(self.part) + '-Frag3.part')
        owned.write_bytes(b'bad')
        unrelated = self.root / 'other.part-Frag3.part'
        unrelated.write_bytes(b'keep')
        self.assertEqual(self.store.prepare(self.output), 1)
        self.assertEqual(self.part.read_bytes(), b'AAA')
        self.assertEqual(json.loads(self.journal.read_text()), {
            'downloader': {'current_fragment': {'index': 1}, 'extra_state': {}}})
        self.assertEqual(self.store.path.read_bytes(), checkpoint)
        self.assertFalse(owned.exists())
        self.assertTrue(unrelated.exists())

    def test_repeated_recovery_is_idempotent_and_journal_ahead_is_repaired(self):
        self.part.write_bytes(b'AAABBB')
        self.checkpoint()
        self.store.prepare(self.output)
        self.journal.write_text('{"downloader":{"current_fragment":{"index":99}}}')
        self.store = hls_resume.CheckpointStore(self.spec)
        self.store.prepare(self.output)
        self.assertEqual(self.part.read_bytes(), b'AAA')
        self.assertEqual(json.loads(self.journal.read_text())['downloader']['current_fragment']['index'], 1)

    def test_reappend_last_fragment_has_no_duplicate(self):
        self.part.write_bytes(b'AAABBBpartial')
        self.checkpoint()
        self.store.prepare(self.output)
        with self.part.open('ab') as stream:
            ctx = {'fragment_index': 2, 'dest_stream': stream}
            self.store.append(ctx, b'BBB', lambda c, b: (c['dest_stream'].write(b), c['dest_stream'].flush()))
        self.assertEqual(self.part.read_bytes(), b'AAABBB')
        self.assertEqual(json.loads(self.store.path.read_text())['end_offset'], 6)

    def test_insufficient_file_and_identity_mismatch_preserve_everything(self):
        for content, identity in [(b'AA', None), (b'AAABBB', 'b' * 64)]:
            with self.subTest(content=content, identity=identity):
                self.part.write_bytes(content)
                checkpoint = self.checkpoint(identity=identity)
                self.journal.write_text('keep')
                with self.assertRaises(hls_resume.ResumeError):
                    self.store.prepare(self.output)
                self.assertEqual(self.part.read_bytes(), content)
                self.assertEqual(self.store.path.read_bytes(), checkpoint)
                self.assertEqual(self.journal.read_text(), 'keep')

    def test_unowned_nonempty_part_is_rejected(self):
        self.part.write_bytes(b'existing')
        with self.assertRaises(hls_resume.ResumeError):
            self.store.prepare(self.output)
        self.assertEqual(self.part.read_bytes(), b'existing')
        self.assertFalse(self.store.path.exists())

    def test_append_failure_and_short_write_never_advance_checkpoint(self):
        self.store.prepare(self.output)
        initial = self.store.path.read_bytes()
        for mode in ['exception', 'short']:
            with self.subTest(mode=mode):
                self.part.write_bytes(b'')
                with self.part.open('ab') as stream:
                    def failed(ctx, content):
                        ctx['dest_stream'].write(content[:1])
                        ctx['dest_stream'].flush()
                        self.journal.write_text('ahead')
                        if mode == 'exception':
                            raise OSError('write failure')
                    with self.assertRaises((OSError, hls_resume.ResumeError)):
                        self.store.append({'fragment_index': 1, 'dest_stream': stream}, b'ABC', failed)
                self.assertEqual(self.store.path.read_bytes(), initial)

    def test_successful_append_persists_exact_offsets_and_checks_consecutive_index(self):
        self.store.prepare(self.output)
        with self.part.open('ab') as stream:
            def write(ctx, content):
                ctx['dest_stream'].write(content)
            self.store.append({'fragment_index': 1, 'dest_stream': stream}, b'ABC', write)
            with self.assertRaises(hls_resume.ResumeError):
                self.store.append({'fragment_index': 3, 'dest_stream': stream}, b'bad', write)
        self.assertEqual(self.part.read_bytes(), b'ABC')
        data = json.loads(self.store.path.read_text())
        self.assertEqual((data['index'], data['start_offset'], data['end_offset']), (1, 0, 3))

    def test_fsync_failure_does_not_advance_checkpoint(self):
        self.store.prepare(self.output)
        initial = self.store.path.read_bytes()
        with self.part.open('ab') as stream:
            with patch.object(hls_resume.os, 'fsync', side_effect=OSError('disk failure')):
                with self.assertRaises(OSError):
                    self.store.append({'fragment_index': 1, 'dest_stream': stream}, b'ABC',
                                      lambda ctx, data: ctx['dest_stream'].write(data))
        self.assertEqual(self.store.path.read_bytes(), initial)

    def test_invalid_metadata_is_rejected_before_truncation(self):
        self.part.write_bytes(b'AAABBBtail')
        self.checkpoint(start=7, end=6)
        before = self.part.read_bytes()
        with self.assertRaises(hls_resume.ResumeError):
            self.store.prepare(self.output)
        self.assertEqual(self.part.read_bytes(), before)
        self.assertFalse(self.journal.exists())

    def test_final_file_is_not_modified(self):
        self.output.write_bytes(b'final')
        self.checkpoint()
        self.assertIsNone(self.store.prepare(self.output))
        self.assertEqual(self.output.read_bytes(), b'final')

    def test_plain_vod_validation_rejects_unsupported_features(self):
        for tag in ['#EXT-X-KEY:METHOD=AES-128,URI="x"', '#EXT-X-MAP:URI="x"',
                    '#EXT-X-BYTERANGE:10', '#EXT-X-PART:URI="x"', '#ANVATO-SEGMENT-INFO:type=ad']:
            with self.subTest(tag=tag), self.assertRaises(hls_resume.ResumeError):
                hls_resume.validate_manifest(MANIFEST.replace('#EXTINF:5,', tag + '\n#EXTINF:5,', 1))
        with self.assertRaises(hls_resume.ResumeError):
            hls_resume.validate_manifest(MANIFEST.replace('#EXT-X-ENDLIST', ''))
        self.assertEqual(hls_resume.validate_manifest(MANIFEST), 2)
        self.assertEqual(hls_resume.validate_manifest(
            MANIFEST.replace('#EXT-X-TARGETDURATION:', '#EXT-X-ALLOW-CACHE:YES\n#EXT-X-TARGETDURATION:')), 2)
        for duration in ['0', '-1', 'NaN', 'Infinity']:
            with self.subTest(duration=duration), self.assertRaises(hls_resume.ResumeError):
                hls_resume.validate_manifest(MANIFEST.replace('#EXTINF:5,', '#EXTINF:' + duration + ',', 1))
        for uri in ['a.mp4', 'https://user@example.test/a.ts', 'a.ts#fragment', 'a.ts#']:
            with self.subTest(uri=uri), self.assertRaises(hls_resume.ResumeError):
                hls_resume.validate_manifest(MANIFEST.replace('\na.ts\n', '\n' + uri + '\n'))


@unittest.skipUnless(os.environ.get('HLS_RESUME_YTDLP'), 'Set HLS_RESUME_YTDLP to test the bundled archive')
class BundledDownloaderTests(unittest.TestCase):
    setUp = CheckpointTests.setUp
    checkpoint = CheckpointTests.checkpoint
    def test_snapshot_child_base_and_real_downloader_rollback(self):
        import http.server
        import threading
        sys_path = __import__('sys').path
        sys_path.insert(0, os.environ['HLS_RESUME_YTDLP'])
        from yt_dlp import YoutubeDL
        from yt_dlp.downloader.hls import HlsFD
        from yt_dlp.downloader.fragment import FragmentFD
        hls_resume.check_private_api(HlsFD, FragmentFD)
        requested = []
        bodies = {'/child/a.ts': b'AAA', '/child/b.ts': b'BBB', '/c.ts': b'CCC'}

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                requested.append(self.path)
                body = bodies.get(self.path)
                self.send_response(200 if body else 404)
                self.send_header('Content-Length', str(len(body or b'')))
                self.end_headers()
                self.wfile.write(body or b'')

            def log_message(self, *args):
                pass

        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        url = 'http://127.0.0.1:{}/child/list.m3u8'.format(server.server_port)
        manifest = ('#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXTINF:5,\na.ts\n'
                    '#EXT-X-DISCONTINUITY\n#EXTINF:5,\nb.ts\n#EXTINF:5,\n../c.ts\n#EXT-X-ENDLIST\n')
        self.spec.write_text(json.dumps({'schema': 1, 'identity': 'a' * 64, 'url': url, 'manifest': manifest}))
        store = hls_resume.CheckpointStore(self.spec)
        self.part.write_bytes(b'AAABBBincomplete')
        self.checkpoint()
        self.journal.write_text('corrupt')
        Path(str(self.part) + '-Frag2.part').write_bytes(b'corrupt-fragment')
        original_download, original_append = HlsFD.real_download, HlsFD._append_fragment
        self.addCleanup(setattr, HlsFD, 'real_download', original_download)
        self.addCleanup(setattr, HlsFD, '_append_fragment', original_append)
        hls_resume.install_adapter(store)
        with YoutubeDL({'quiet': True, 'noprogress': True}) as ydl:
            downloader = HlsFD(ydl, {'quiet': True, 'noprogress': True})
            self.assertTrue(downloader.real_download(str(self.output), {
                'url': url, 'ext': 'mp4', 'protocol': 'm3u8_native', 'id': 'test'}))
        self.assertEqual(requested, ['/child/b.ts', '/c.ts'])
        self.assertEqual(self.output.read_bytes(), b'AAABBBCCC')
        self.assertFalse(self.part.exists())
        self.assertEqual(json.loads(store.path.read_text())['index'], 3)


if __name__ == '__main__':
    unittest.main()
