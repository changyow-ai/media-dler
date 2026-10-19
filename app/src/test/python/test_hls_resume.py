import importlib.util
import hashlib
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


class AdCacheTests(unittest.TestCase):
    setUp = CheckpointTests.setUp
    def ad_store(self, bodies=None, manifest=MANIFEST, url='https://example.test/child/list.m3u8'):
        sys.path.insert(0, str(MODULE.parent))
        import hls_ad_filter as ads
        directory = self.resume / 'ad-cache'
        directory.mkdir(exist_ok=True)
        entries = []
        for uri, body in (bodies or {'a.ts': b'AAA', '../b.ts': b'BBB'}).items():
            resolved = hls_resume.urljoin(url, uri)
            name = hashlib.sha256(resolved.encode()).hexdigest() + '.ts'
            (directory / name).write_bytes(body)
            entries.append({'url': resolved, 'path': 'ad-cache/' + name, 'size': len(body),
                            'sha256': hashlib.sha256(body).hexdigest()})
        plan = {'schema': 1, 'policy': ads.POLICY, 'catalog_sha256': 'c' * 64,
                'snapshot_identity': 'b' * 64, 'manifest': manifest, 'skipped_ads': 0,
                'skipped_ad_segments': 0, 'skipped_ad_seconds': 0,
                'skipped_segment_indices': [], 'cached_segments': entries}
        plan['identity'] = ads.plan_identity(plan)
        path = self.resume / 'ad-plan.json'
        path.write_text(json.dumps(plan))
        self.spec.write_text(json.dumps({'schema': 1, 'identity': plan['identity'], 'url': url,
                                        'manifest': manifest, 'ad_plan': str(path)}))
        return hls_resume.CheckpointStore(self.spec), plan, path

    def test_cached_copy_is_disposable_and_expected_index_is_required(self):
        store, plan, _ = self.ad_store()
        store.prepare(self.output)
        ctx = {'tmpfilename': str(store.part), 'fragment_index': 1}
        self.assertTrue(store.copy_cached_fragment(ctx, store.fragment_urls[0]))
        disposable = Path(ctx['fragment_filename_sanitized'])
        self.assertEqual(disposable.read_bytes(), b'AAA')
        self.assertEqual(disposable, Path(str(store.part) + '-Frag1'))
        disposable.unlink()
        self.assertEqual((self.resume / plan['cached_segments'][0]['path']).read_bytes(), b'AAA')
        with self.assertRaises(hls_resume.ResumeError):
            store.copy_cached_fragment(dict(ctx, fragment_index=2), store.fragment_urls[0])

    def test_missing_or_corrupt_payload_falls_back_without_plan_changes(self):
        store, plan, path = self.ad_store()
        before = path.read_bytes()
        store.prepare(self.output)
        payload = self.resume / plan['cached_segments'][0]['path']
        for mode in ('corrupt', 'missing'):
            if mode == 'corrupt':
                payload.write_bytes(b'bad')
            else:
                payload.unlink()
            self.assertFalse(store.copy_cached_fragment({'tmpfilename': str(store.part), 'fragment_index': 1}, store.fragment_urls[0]))
            self.assertEqual(path.read_bytes(), before)

    def test_plan_mismatch_and_escape_refused_before_checkpoint_or_part_changes(self):
        store, plan, path = self.ad_store()
        original = path.read_bytes()
        self.part.write_bytes(b'untouched')
        for mode in ('identity', 'escape', 'wrong_url', 'symlink'):
            with self.subTest(mode=mode):
                path.write_bytes(original)
                changed = json.loads(original)
                if mode == 'identity':
                    changed['identity'] = 'd' * 64
                elif mode == 'escape':
                    changed['cached_segments'][0]['path'] = '../outside.ts'
                elif mode == 'wrong_url':
                    changed['cached_segments'][0]['url'] = 'https://other.test/a.ts'
                else:
                    payload = self.resume / changed['cached_segments'][0]['path']
                    payload.unlink()
                    payload.symlink_to(self.spec)
                path.write_text(json.dumps(changed))
                with self.assertRaises(hls_resume.ResumeError):
                    hls_resume.CheckpointStore(self.spec)
                self.assertEqual(self.part.read_bytes(), b'untouched')
                self.assertFalse(store.path.exists())


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
        original_fragment = HlsFD._download_fragment
        self.addCleanup(setattr, HlsFD, '_download_fragment', original_fragment)
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


    def test_retained_cache_works_with_inaccessible_urls_and_survives_rollback(self):
        import http.server
        import threading
        sys.path.insert(0, os.environ['HLS_RESUME_YTDLP'])
        from yt_dlp import YoutubeDL
        from yt_dlp.downloader.hls import HlsFD
        from yt_dlp.downloader.fragment import FragmentFD
        hls_resume.check_private_api(HlsFD, FragmentFD)
        requested = []
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                requested.append(self.path)
                body = b'CCC' if self.path == '/child/c.ts' else b''
                self.send_response(200 if body else 404)
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            def log_message(self, *args):
                pass
        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        url = 'http://127.0.0.1:{}/child/list.m3u8'.format(server.server_port)
        manifest = '#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXTINF:5,\na.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:5,\nb.ts\n#EXTINF:5,\nc.ts\n#EXT-X-ENDLIST\n'
        store, plan, path = AdCacheTests.ad_store(self, bodies={'a.ts': b'AAA', 'b.ts': b'BBB'}, manifest=manifest, url=url)
        originals = (HlsFD.real_download, HlsFD._append_fragment, HlsFD._download_fragment)
        def restore():
            HlsFD.real_download, HlsFD._append_fragment, HlsFD._download_fragment = originals
        self.addCleanup(restore)
        for attempt in range(2):
            if attempt:
                self.output.unlink()
                self.part.write_bytes(b'AAABBBincomplete')
                store.path.write_text(json.dumps({'schema': 1, 'identity': plan['identity'], 'filename': self.output.name,
                                                  'index': 2, 'start_offset': 3, 'end_offset': 6}))
            restore()
            store = hls_resume.CheckpointStore(self.spec)
            hls_resume.install_adapter(store)
            with YoutubeDL({'quiet': True, 'noprogress': True}) as ydl:
                downloader = HlsFD(ydl, {'quiet': True, 'noprogress': True})
                self.assertTrue(downloader.real_download(str(self.output), {'url': url, 'ext': 'mp4', 'protocol': 'm3u8_native', 'id': 'cache'}))
            self.assertEqual(self.output.read_bytes(), b'AAABBBCCC')
            self.assertEqual(json.loads(store.path.read_text())['index'], 3)
            self.assertTrue(all((self.resume / e['path']).read_bytes() in (b'AAA', b'BBB') for e in plan['cached_segments']))
            self.assertFalse(list(self.root.glob('*.part-Frag*')))
        self.assertEqual(requested, ['/child/c.ts', '/child/c.ts'])


    def test_cache_network_orders_all_cached_and_corrupt_fallback_preserve_progress(self):
        import http.server
        import threading
        sys.path.insert(0, os.environ['HLS_RESUME_YTDLP'])
        from yt_dlp import YoutubeDL
        from yt_dlp.downloader.hls import HlsFD
        requested = []
        accessible = set()
        bodies = {'a.ts': b'AAA', 'b.ts': b'BBBB', 'c.ts': b'CCCCC'}
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                requested.append(self.path)
                name = self.path.rsplit('/', 1)[-1]
                body = bodies.get(name, b'') if name in accessible else b''
                self.send_response(200 if body else 404)
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            def log_message(self, *args):
                pass
        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        originals = (HlsFD.real_download, HlsFD._append_fragment, HlsFD._download_fragment)
        def restore():
            HlsFD.real_download, HlsFD._append_fragment, HlsFD._download_fragment = originals
        self.addCleanup(restore)
        manifest = '#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXTINF:5,\na.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:5,\nb.ts\n#EXTINF:5,\nc.ts\n#EXT-X-ENDLIST\n'
        for mode, cached_names, broken in [('middle', ['b.ts'], None), ('all', list(bodies), None),
                                           ('corrupt', ['b.ts'], 'corrupt'), ('missing', ['b.ts'], 'missing')]:
            with self.subTest(mode=mode):
                restore()
                requested.clear()
                job = self.root / mode
                self.resume = job / '.resume'
                self.resume.mkdir(parents=True)
                self.spec = self.resume / 'session.json'
                self.output = job / 'media.mp4'
                self.part = Path(str(self.output) + '.part')
                url = 'http://127.0.0.1:{}/{}/list.m3u8'.format(server.server_port, mode)
                store, plan, plan_path = AdCacheTests.ad_store(self, bodies={name: bodies[name] for name in cached_names}, manifest=manifest, url=url)
                accessible.clear()
                accessible.update(set(bodies) - set(cached_names))
                if broken:
                    payload = self.resume / plan['cached_segments'][0]['path']
                    if broken == 'corrupt':
                        payload.write_bytes(b'bad')
                    else:
                        payload.unlink()
                    accessible.add('b.ts')
                frozen = plan_path.read_bytes()
                hls_resume.install_adapter(store)
                progress = []
                with YoutubeDL({'quiet': True, 'noprogress': True}) as ydl:
                    downloader = HlsFD(ydl, {'quiet': True, 'noprogress': True})
                    downloader.add_progress_hook(lambda state: progress.append(dict(state)))
                    self.assertTrue(downloader.real_download(str(self.output), {'url': url, 'ext': 'mp4', 'protocol': 'm3u8_native', 'id': mode}))
                self.assertEqual(self.output.read_bytes(), b'AAABBBBCCCCC')
                self.assertEqual(json.loads(store.path.read_text())['index'], 3)
                self.assertEqual(plan_path.read_bytes(), frozen)
                self.assertEqual(requested, ['/{}/{}'.format(mode, name) for name in bodies if name in accessible])
                completions = {state.get('fragment_index'): state.get('downloaded_bytes') for state in progress
                               if state.get('status') == 'downloading' and state.get('fragment_index') in (1, 2, 3)}
                self.assertEqual(completions[3], 12)
                self.assertEqual(progress[-1]['downloaded_bytes'], 12)
                self.assertEqual(progress[-1]['status'], 'finished')
                if mode == 'all':
                    self.assertEqual([(state['fragment_index'], state['downloaded_bytes']) for state in progress
                                      if state.get('status') == 'downloading'], [(1, 3), (2, 7), (3, 12)])


if __name__ == '__main__':
    unittest.main()
