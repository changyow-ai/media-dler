import copy
from email.message import Message
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
ASSETS = Path(__file__).resolve().parents[2] / 'main/assets'
spec = importlib.util.spec_from_file_location('hls_ad_filter', ASSETS / 'hls_ad_filter.py')
ads = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ads)
CATALOG = ASSETS / 'hls_ad_fingerprints.json'
BASE = 'https://example.test/child/list.m3u8'
MANIFEST = ('#EXTM3U\r\n#EXT-X-TARGETDURATION:5\r\n#EXTINF:5,normal\r\nnormal.ts\r\n'
            '#EXT-X-DISCONTINUITY\r\n# ppvod-ad-injected v1\r\n#EXTINF:4,ad\r\nad.ts\r\n'
            '#EXT-X-DISCONTINUITY\r\n#EXTINF:5,tail\r\ntail.ts\r\n#EXT-X-ENDLIST\r\n')


class FakeResponse(io.BytesIO):
    url = 'https://example.test/segment.ts'

    def __init__(self, body, status=200, content_length='auto'):
        super().__init__(body)
        self.status = status
        self.headers = Message()
        if content_length is not None:
            self.headers['Content-Length'] = str(len(body) if content_length == 'auto' else content_length)


class FilterTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / '.resume'
        self.root.mkdir()
        self.input = self.root / 'ad-input.json'
        self.plan = self.root / 'ad-plan.json'
        self.original = {'schema': 1, 'identity': 'a' * 64, 'url': BASE, 'manifest': MANIFEST, 'headers': {}}
        self.input.write_text(json.dumps(self.original))
        self.catalog = self.root / 'catalog.json'
        self.catalog.write_bytes(CATALOG.read_bytes())
        self.fp = json.loads(CATALOG.read_text())['signatures'][0]['fingerprint']
        self.open = patch.object(ads.SegmentCache, '__init__', autospec=True, side_effect=self.cache_init)
        self.real_init = ads.SegmentCache.__init__
        self.open.start()
        self.addCleanup(self.open.stop)

    def cache_init(self, cache, *args, **kwargs):
        self.real_init(cache, *args, **kwargs)
        cache.opener.open = lambda *a, **kw: FakeResponse(b'complete-segment')

    def classify(self, matched=(1,), failure=None, report_progress=False):
        def candidate(region, *args):
            if failure:
                raise failure
            fp = copy.deepcopy(self.fp)
            if region['index'] not in matched:
                fp['streams']['audio']['packet_sequence_sha256'] = 'f' * 64
            return fp
        with patch.object(ads, 'candidate_fingerprint', side_effect=candidate):
            return ads.classify(self.input, self.catalog, self.plan, '/usr/bin/ffmpeg', '/usr/bin/ffprobe',
                                report_progress=report_progress)

    @staticmethod
    def progress_events(output):
        return [json.loads(line.removeprefix('HLS_AD_PROGRESS '))
                for line in output.getvalue().splitlines() if line.startswith('HLS_AD_PROGRESS ')]

    def test_fast_progress_separates_processed_nonads_unknown_and_ad_counts(self):
        output = io.StringIO()
        with patch('sys.stdout', output):
            plan = self.classify(report_progress=True)
        events = self.progress_events(output)
        self.assertEqual(events[0]['stage'], 'source')
        self.assertEqual((events[0]['completed'], events[0]['total'], events[0]['unknown']), (0, 3, 0))
        self.assertEqual([e['completed'] for e in events if e['stage'] == 'scan'], [1, 2, 3])
        self.assertTrue(all(e['ads'] is None for e in events[:-1]))
        self.assertEqual(events[-1]['stage'], 'complete')
        self.assertEqual((events[-1]['completed'], events[-1]['total'], events[-1]['unknown'], events[-1]['ads']),
                         (3, 3, 0, 1))
        self.assertEqual(events[-1]['elapsed_seconds'], plan['cost']['elapsed_seconds'])
        # Starting the next source/probe must carry the latest completed count.
        last_completed = 0
        for event in events:
            self.assertGreaterEqual(event['completed'], last_completed)
            last_completed = event['completed']

    def test_failed_and_budget_preserved_regions_count_as_processed_unknown(self):
        for limited in (False, True):
            output = io.StringIO()
            with self.subTest(limited=limited), patch('sys.stdout', output), \
                 patch.object(ads, 'MAX_REGIONS', 1 if limited else 16):
                self.classify(failure=ads.Unknown('decode_failed'), report_progress=True)
            final = self.progress_events(output)[-1]
            self.assertEqual((final['completed'], final['total'], final['unknown'], final['ads']), (3, 3, 3, 0))
            self.plan.unlink()

    def test_warm_fast_progress_restores_saved_rows_and_time_without_native_work(self):
        plan = self.classify()
        output = io.StringIO()
        with patch('sys.stdout', output), \
             patch.object(ads.SegmentCache, 'fetch', side_effect=AssertionError('must not fetch')), \
             patch.object(ads, 'candidate_fingerprint', side_effect=AssertionError('must not decode')):
            restored = ads.classify(self.input, self.catalog, self.plan, '/ffmpeg', '/ffprobe', report_progress=True)
        self.assertEqual(restored, plan)
        events = self.progress_events(output)
        self.assertEqual([e['stage'] for e in events], ['restoring', 'complete'])
        for event in events:
            self.assertEqual((event['completed'], event['total'], event['unknown'], event['ads']), (3, 3, 0, 1))
            self.assertEqual(event['elapsed_seconds'], plan['cost']['elapsed_seconds'])

    def test_unsupported_fast_enumeration_does_not_claim_known_total_or_unknown_count(self):
        self.original['manifest'] = MANIFEST.replace('#EXT-X-TARGETDURATION:5', '#EXT-X-KEY:METHOD=AES-128,URI="key"')
        self.input.write_text(json.dumps(self.original))
        for warm in (False, True):
            output = io.StringIO()
            with self.subTest(warm=warm), patch('sys.stdout', output):
                self.classify(report_progress=True)
            for event in self.progress_events(output):
                self.assertIsNone(event['total'])
                self.assertIsNone(event['unknown'])

    def test_nested_default_scan_does_not_emit_structured_snapshots_and_cli_enables_them(self):
        output = io.StringIO()
        with patch('sys.stdout', output):
            self.classify()
        self.assertFalse(self.progress_events(output))
        with patch.object(ads, 'classify') as classify, patch.object(ads.signal, 'signal'):
            ads.main(['--input', '/input', '--catalog', '/catalog', '--plan', '/plan',
                      '--ffmpeg', '/ffmpeg', '--ffprobe', '/ffprobe'])
        classify.assert_called_once_with('/input', '/catalog', '/plan', '/ffmpeg', '/ffprobe', report_progress=True)

    def test_marked_full_content_match_omits_only_extinf_and_uri_verbatim(self):
        plan = self.classify()
        self.assertEqual(plan['skipped_ads'], 1)
        self.assertEqual(plan['skipped_segment_indices'], [1])
        self.assertEqual(plan['skipped_ad_seconds'], 4)
        self.assertEqual(plan['manifest'], MANIFEST.replace('#EXTINF:4,ad\r\nad.ts\r\n', ''))
        self.assertIn('# ppvod-ad-injected v1\r\n', plan['manifest'])
        self.assertEqual({x['url'] for x in plan['cached_segments']}, {BASE.replace('list.m3u8', 'normal.ts'), BASE.replace('list.m3u8', 'tail.ts')})
        self.assertEqual(plan['identity'], ads.plan_identity(plan))

    def test_unmarked_hit_matches_but_unknown_marked_normal_stays(self):
        self.original['manifest'] = MANIFEST.replace('# ppvod-ad-injected v1\r\n', '')
        self.input.write_text(json.dumps(self.original))
        plan = self.classify()
        self.assertEqual(plan['skipped_ads'], 1)
        self.plan.unlink()
        plan = self.classify(matched=())
        self.assertEqual(plan['manifest'], self.original['manifest'])
        self.assertEqual(plan['skipped_segment_indices'], [])

    def test_frozen_plan_reused_without_fetch_or_reclassify_even_if_cache_gone(self):
        first = self.classify()
        before = self.plan.read_bytes()
        for p in (self.root / 'ad-cache').glob('*.ts'):
            p.unlink()
        with patch.object(ads.SegmentCache, 'fetch', side_effect=AssertionError('must not fetch')), patch.object(ads, 'candidate_fingerprint', side_effect=AssertionError('must not reclassify')):
            self.assertEqual(ads.classify(self.input, self.catalog, self.plan, '/ffmpeg', '/ffprobe'), first)
        self.assertEqual(self.plan.read_bytes(), before)

    def test_frozen_unknown_does_not_change_after_network_recovers(self):
        first = self.classify(failure=ads.Unknown('network failure'))
        self.assertEqual(first['skipped_ads'], 0)
        self.assertEqual(self.classify()['identity'], first['identity'])
        self.assertEqual(self.classify()['skipped_ads'], 0)

    def test_snapshot_catalog_and_plan_corruption_refuse_existing_plan(self):
        first = self.classify()
        before = self.plan.read_bytes()
        for mode in ('snapshot', 'catalog', 'identity', 'manifest'):
            with self.subTest(mode=mode):
                self.plan.write_bytes(before)
                self.input.write_text(json.dumps(self.original))
                self.catalog.write_bytes(CATALOG.read_bytes())
                if mode == 'snapshot':
                    original = dict(self.original, identity='b' * 64)
                    self.input.write_text(json.dumps(original))
                elif mode == 'catalog':
                    self.catalog.write_bytes(CATALOG.read_bytes() + b'\n')
                else:
                    corrupt = copy.deepcopy(first)
                    corrupt[mode] = 'c' * 64 if mode == 'identity' else MANIFEST
                    self.plan.write_text(json.dumps(corrupt))
                committed = self.plan.read_bytes()
                with self.assertRaises(ads.FilterError):
                    self.classify()
                self.assertEqual(self.plan.read_bytes(), committed)

    def test_budget_and_candidate_errors_retain_all_media(self):
        for limit in ('MAX_SECONDS', 'MAX_SEGMENTS', 'MAX_REGIONS'):
            with self.subTest(limit=limit), patch.object(ads, limit, 0):
                plan = self.classify()
                self.assertEqual(plan['skipped_ads'], 0)
                self.assertEqual(plan['manifest'], MANIFEST)
                self.plan.unlink()
        plan = self.classify(failure=ads.Unknown('unsupported_codec'))
        self.assertEqual(plan['manifest'], MANIFEST)

    def test_would_remove_all_media_is_left_unchanged(self):
        plan = self.classify(matched=(0, 1, 2))
        self.assertEqual(plan['skipped_ads'], 0)
        self.assertEqual(plan['manifest'], MANIFEST)
        self.assertTrue(all(r['reason'] == 'would_remove_all_media' for r in plan['regions']))

    def test_cancellation_removes_temp_does_not_commit_plan_but_keeps_complete_cache(self):
        with self.assertRaises(KeyboardInterrupt):
            self.classify(failure=KeyboardInterrupt())
        self.assertFalse(self.plan.exists())
        self.assertFalse(list(self.root.glob('.ad-scan-*')))
        index = json.loads((self.root / 'ad-cache/index.json').read_text())
        self.assertEqual(len(index['segments']), 1)
        self.assertIsNotNone(ads.valid_cached(self.root, index['segments'][0]))
        self.assertEqual(self.classify()['skipped_ads'], 1)

    def test_unsupported_complete_manifest_is_frozen_unchanged(self):
        self.original['manifest'] = MANIFEST.replace('#EXT-X-TARGETDURATION:5', '#EXT-X-KEY:METHOD=AES-128,URI="key"')
        self.input.write_text(json.dumps(self.original))
        first = self.classify()
        self.assertEqual(first['manifest'], self.original['manifest'])
        self.assertEqual(self.classify(), first)

    def test_parser_rejects_incomplete_and_unsupported_and_includes_edges(self):
        _, regions, segments = ads.parse_manifest(MANIFEST, BASE)
        self.assertEqual([r['start_boundary'] for r in regions], ['playlist_start', 'discontinuity', 'discontinuity'])
        self.assertEqual(regions[-1]['end_boundary'], 'playlist_end')
        self.assertEqual(len(segments), 3)
        for changed in [MANIFEST.replace('#EXT-X-ENDLIST', ''), MANIFEST.replace('normal.ts', 'file:///tmp/a.ts'),
                        MANIFEST.replace('#EXTINF:5,normal', '#EXTINF:NaN,normal'),
                        MANIFEST.replace('#EXTINF:5,normal', '#EXT-X-BYTERANGE:8\n#EXTINF:5,normal'),
                        MANIFEST.replace('#EXTINF:5,normal', '#EXT-X-MAP:URI="x"\n#EXTINF:5,normal')]:
            with self.subTest(changed=changed), self.assertRaises(ads.Unknown):
                ads.parse_manifest(changed, BASE)

    def test_cache_paths_and_metadata_fail_closed(self):
        plan = self.classify()
        entry = plan['cached_segments'][0]
        for change in ({'path': '../outside.ts'}, {'size': -1}, {'sha256': 'bad'}):
            with self.subTest(change=change), self.assertRaises(ads.FilterError):
                ads.cache_path(self.root, dict(entry, **change))
        path = self.root / entry['path']
        path.unlink()
        path.symlink_to(self.input)
        with self.assertRaises(ads.FilterError):
            ads.cache_path(self.root, entry)


    def test_committed_plan_publication_cannot_replace_existing_decision(self):
        self.plan.write_text('previous committed decision')
        with self.assertRaises(FileExistsError):
            ads.atomic_json(self.plan, {'different': True}, replace=False)
        self.assertEqual(self.plan.read_text(), 'previous committed decision')
        self.assertFalse(list(self.root.glob('ad-plan.json.*.tmp')))

    def test_remux_warning_is_unknown_and_keeps_manifest(self):
        with patch.object(ads, 'run_json', return_value={'streams': [{'codec_name': 'h264'}, {'codec_name': 'aac'}]}), patch.object(ads.subprocess, 'run', return_value=type('Result', (), {'stderr': 'timestamp warning'})()):
            plan = ads.classify(self.input, self.catalog, self.plan, '/ffmpeg', '/ffprobe')
        self.assertEqual(plan['manifest'], MANIFEST)
        self.assertTrue(all('remux_warning' in r['reason'] for r in plan['regions']))
        self.assertFalse(list(self.root.glob('.ad-scan-*')))


    def test_rate_input_validates_and_cap_changes_do_not_reclassify_frozen_plan(self):
        first = self.classify()
        for rate in (None, 1024, 2048):
            self.input.write_text(json.dumps(dict(self.original, rate_limit_bytes_per_second=rate)))
            with patch.object(ads.SegmentCache, 'fetch', side_effect=AssertionError('no fetch on retry')):
                self.assertEqual(self.classify()['identity'], first['identity'])
        for bad in (0, -1, True, '1024', 1.5):
            with self.subTest(rate=bad):
                self.input.write_text(json.dumps(dict(self.original, rate_limit_bytes_per_second=bad)))
                with self.assertRaises(ads.FilterError):
                    self.classify()

    def test_incomplete_candidate_is_unknown_without_cached_mapping(self):
        # read(amt) can return a short body then EOF instead of raising.
        def init(cache, *args, **kwargs):
            self.real_init(cache, *args, **kwargs)
            cache.opener.open = lambda *a, **kw: FakeResponse(b'ABC', content_length=10)
        ads.SegmentCache.__init__.side_effect = init
        plan = self.classify()
        self.assertEqual(plan['manifest'], MANIFEST)
        self.assertEqual(plan['cached_segments'], [])
        self.assertTrue(all('content_length_mismatch' in r['reason'] for r in plan['regions']))
        self.assertFalse((self.root / 'ad-cache/index.json').exists())
        # A completed unknown decision stays frozen despite transaction retry.
        with patch.object(ads.SegmentCache, 'fetch', side_effect=AssertionError('frozen plan must not retry')):
            self.assertEqual(self.classify()['identity'], plan['identity'])


class DownloadTests(unittest.TestCase):
    def test_partial_cancelled_cache_is_never_indexed_or_reused(self):
        class CancelledResponse(FakeResponse):
            def read(self, n):
                if self.tell():
                    raise KeyboardInterrupt()
                return super().read(n)
        with tempfile.TemporaryDirectory() as temp:
            cache = ads.SegmentCache(Path(temp), 'a' * 64, {})
            with patch.object(cache.opener, 'open', return_value=CancelledResponse(b'first partial bytes', content_length=100)) as request:
                with self.assertRaises(KeyboardInterrupt):
                    cache.fetch('https://example.test/a.ts', 500)
            self.assertEqual(request.call_count, 1)
            self.assertFalse(cache.index.exists())
            self.assertFalse(list(cache.directory.iterdir()))

    def test_cache_bounds_and_revalidation_and_duplicate_url_reuse(self):
        with tempfile.TemporaryDirectory() as temp:
            cache = ads.SegmentCache(Path(temp), 'a' * 64, {})
            url = 'https://example.test/a.ts'
            with patch.object(cache.opener, 'open', return_value=FakeResponse(b'abc')) as request:
                first = cache.fetch(url, 10)
                self.assertEqual(cache.fetch(url, 10), first)
                self.assertEqual(request.call_count, 1)
            self.assertEqual((cache.network_bytes, cache.reused_bytes), (3, 3))
            (Path(temp) / first['path']).write_bytes(b'bad')
            again = ads.SegmentCache(Path(temp), 'a' * 64, {})
            self.assertNotIn(url, again.entries)
            with patch.object(again.opener, 'open', return_value=FakeResponse(b'too many bytes', content_length=None)) as request:
                with self.assertRaises(ads.Unknown):
                    again.fetch(url, 4)
            self.assertEqual(request.call_count, 1)
            self.assertEqual(again.network_bytes, 4)
            self.assertFalse(list(cache.directory.glob('*.part')))


    def test_truncated_real_http_response_not_indexed_then_full_network_retry_succeeds(self):
        import http.server
        import threading
        requested = []
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                requested.append(self.path)
                self.send_response(200)
                self.send_header('Content-Length', '10')
                self.end_headers()
                self.wfile.write(b'ABC' if len(requested) == 1 else b'0123456789')
                self.close_connection = True
            def log_message(self, *args):
                pass
        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        with tempfile.TemporaryDirectory() as temp:
            cache = ads.SegmentCache(Path(temp), 'a' * 64, {})
            url = 'http://127.0.0.1:{}/candidate.ts'.format(server.server_port)
            entry = cache.fetch(url, 100)
            self.assertEqual(entry['size'], 10)
            self.assertEqual(cache.network_bytes, 13)
            self.assertEqual(entry['sha256'], ads.digest(b'0123456789'))
            self.assertFalse(list(cache.directory.glob('*.part')))
            self.assertEqual((Path(temp) / entry['path']).read_bytes(), b'0123456789')
            self.assertEqual(requested, ['/candidate.ts', '/candidate.ts'])

    def test_non_200_partial_and_invalid_lengths_never_publish_cache(self):
        cases = [(206, 3), (204, 3), (200, 'bad'), (200, -1), (200, '3,3')]
        for status, length in cases:
            with self.subTest(status=status, length=length), tempfile.TemporaryDirectory() as temp:
                cache = ads.SegmentCache(Path(temp), 'a' * 64, {})
                with patch.object(cache.opener, 'open', return_value=FakeResponse(b'ABC', status=status, content_length=length)) as request:
                    with self.assertRaises(ads.Unknown):
                        cache.fetch('https://example.test/a.ts', 100)
                self.assertEqual(request.call_count, 1)
                self.assertFalse(cache.entries)
                self.assertFalse(cache.index.exists())
                self.assertFalse(list(cache.directory.iterdir()))

    def test_rate_throttle_counts_network_only_and_excludes_sleep_from_deadline(self):
        class Clock:
            now = 0.0
            def monotonic(self):
                return self.now
            def sleep(self, duration):
                self.now += duration
        clock = Clock()
        with tempfile.TemporaryDirectory() as temp, patch.object(ads.time, 'monotonic', side_effect=clock.monotonic), patch.object(ads.time, 'sleep', side_effect=clock.sleep) as sleep:
            cache = ads.SegmentCache(Path(temp), 'a' * 64, {}, rate_limit=1)
            with patch.object(cache.opener, 'open', return_value=FakeResponse(b'x' * 40)):
                first = cache.fetch('https://example.test/a.ts', 100)
            # Forty seconds intentionally exceeds the server's30s deadline,
            # yet all40bytes are complete and valid, at exactly1byte/second.
            self.assertEqual(clock.now, 40)
            self.assertEqual(cache.network_bytes, 40)
            self.assertEqual(first['size'], 40)
            calls = sleep.call_count
            self.assertEqual(cache.fetch('https://example.test/a.ts', 100), first)
            self.assertEqual(sleep.call_count, calls)
            self.assertEqual(cache.reused_bytes, 40)
            with patch.object(cache.opener, 'open', return_value=FakeResponse(b'y' * 10)):
                cache.fetch('https://example.test/b.ts', 100)
            self.assertEqual(clock.now, 50)
            self.assertEqual(cache.network_bytes, 50)

    def test_cancellation_during_rate_sleep_never_publishes_partial_cache(self):
        with tempfile.TemporaryDirectory() as temp:
            cache = ads.SegmentCache(Path(temp), 'a' * 64, {}, rate_limit=1)
            with patch.object(cache.opener, 'open', return_value=FakeResponse(b'ABC')) as request, patch.object(ads.time, 'sleep', side_effect=SystemExit(130)):
                with self.assertRaises(SystemExit):
                    cache.fetch('https://example.test/a.ts', 100)
            self.assertEqual(request.call_count, 1)
            self.assertFalse(cache.index.exists())
            self.assertFalse(list(cache.directory.iterdir()))


    def test_two_truncated_responses_stop_and_never_commit_partial_data(self):
        with tempfile.TemporaryDirectory() as temp:
            cache = ads.SegmentCache(Path(temp), 'a' * 64, {})
            with patch.object(cache.opener, 'open', side_effect=[FakeResponse(b'ABC', content_length=10), FakeResponse(b'DEF', content_length=10)]) as request:
                with self.assertRaisesRegex(ads.Unknown, 'content_length_mismatch'):
                    cache.fetch('https://example.test/a.ts', 100)
            self.assertEqual(request.call_count, 2)
            self.assertEqual(cache.network_bytes, 6)
            self.assertFalse(cache.entries)
            self.assertFalse(cache.index.exists())
            self.assertFalse(list(cache.directory.iterdir()))

    def test_discarded_partial_bytes_exhaust_global_budget_before_retry(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(ads, 'MAX_TOTAL_BYTES', 12):
            cache = ads.SegmentCache(Path(temp), 'a' * 64, {})
            with patch.object(cache.opener, 'open', side_effect=[FakeResponse(b'ABC', content_length=10), FakeResponse(b'0123456789')]) as request:
                with self.assertRaisesRegex(ads.Unknown, 'download_byte_budget'):
                    cache.fetch('https://example.test/a.ts', 100)
            # The second response needs10bytes; only9networkbytes remain.
            self.assertEqual(request.call_count, 2)
            self.assertEqual(cache.network_bytes, 3)
            self.assertFalse(cache.entries)
            self.assertFalse(cache.index.exists())
            self.assertFalse(list(cache.directory.iterdir()))

    def test_incomplete_read_partial_is_charged_and_only_full_retry_is_indexed(self):
        class BrokenResponse(FakeResponse):
            def read(self, n):
                raise ads.IncompleteRead(b'ABC', 7)
        clock = [0.0]
        def sleep(duration):
            clock[0] += duration
        with tempfile.TemporaryDirectory() as temp, patch.object(ads.time, 'monotonic', side_effect=lambda: clock[0]), patch.object(ads.time, 'sleep', side_effect=sleep):
            cache = ads.SegmentCache(Path(temp), 'a' * 64, {}, rate_limit=1)
            with patch.object(cache.opener, 'open', side_effect=[BrokenResponse(b'', content_length=10), FakeResponse(b'0123456789')]) as request:
                entry = cache.fetch('https://example.test/a.ts', 100)
            self.assertEqual(request.call_count, 2)
            self.assertEqual(cache.network_bytes, 13)
            self.assertEqual(clock[0], 13)
            self.assertEqual(entry['sha256'], ads.digest(b'0123456789'))
            self.assertEqual((Path(temp) / entry['path']).read_bytes(), b'0123456789')
            self.assertFalse(list(cache.directory.glob('*.part')))

    def test_transport_and_5xx_retry_once_but_4xx_and_filesystem_errors_do_not(self):
        url = 'https://example.test/a.ts'
        for error, retry in [(TimeoutError('socket timed out'), True),
                             (ads.URLError(ConnectionResetError('reset')), True),
                             (ads.HTTPError(url, 503, 'unavailable', Message(), None), True),
                             (ads.HTTPError(url, 404, 'missing', Message(), None), False),
                             (OSError('disk error'), False)]:
            with self.subTest(error=error), tempfile.TemporaryDirectory() as temp:
                cache = ads.SegmentCache(Path(temp), 'a' * 64, {})
                with patch.object(cache.opener, 'open', side_effect=[error, FakeResponse(b'complete')]) as request:
                    if retry:
                        entry = cache.fetch(url, 100)
                        self.assertEqual(entry['sha256'], ads.digest(b'complete'))
                    else:
                        with self.assertRaises(type(error)):
                            cache.fetch(url, 100)
                self.assertEqual(request.call_count, 2 if retry else 1)
                self.assertFalse(list(cache.directory.glob('*.part')))


    def test_filesystem_timeout_is_not_mistaken_for_retryable_socket_timeout(self):
        with tempfile.TemporaryDirectory() as temp:
            cache = ads.SegmentCache(Path(temp), 'a' * 64, {})
            with patch.object(cache.opener, 'open', return_value=FakeResponse(b'complete')) as request, patch.object(ads.os, 'fsync', side_effect=TimeoutError('filesystem timeout')):
                with self.assertRaises(TimeoutError):
                    cache.fetch('https://example.test/a.ts', 100)
            self.assertEqual(request.call_count, 1)
            self.assertFalse(cache.index.exists())
            self.assertFalse(list(cache.directory.iterdir()))


class FingerprintTests(unittest.TestCase):
    def data(self):
        fp = json.loads(CATALOG.read_text())['signatures'][0]['fingerprint']
        return {'streams': [dict(fp['streams'][kind]['metadata'], codec_type=kind, index=index)
                            for index, kind in enumerate(['video', 'audio'])],
                'packets': [{'stream_index': index, 'data_hash': 'SHA256:' + h * 64, 'pts': 1}
                            for index, h in [(0, 'a'), (1, 'b'), (0, 'c'), (1, 'd')]]}

    def fp(self, data):
        with patch.object(ads, 'run_json', return_value=data):
            return ads.fingerprint('/fixture.mp4', '/ffprobe')

    def test_modified_tail_or_added_audio_rejects_even_when_first_packet_identical(self):
        original = self.data()
        reference = self.fp(original)
        for change in ('tail', 'extra_audio', 'missing_tail', 'codec'):
            changed = copy.deepcopy(original)
            if change == 'tail':
                changed['packets'][2]['data_hash'] = 'SHA256:' + 'f' * 64
            elif change == 'extra_audio':
                changed['packets'].append(copy.deepcopy(changed['packets'][1]))
            elif change == 'missing_tail':
                changed['packets'].pop()
            else:
                changed['streams'][0]['codec_name'] = 'hevc'
            with self.subTest(change=change):
                if change == 'codec':
                    with self.assertRaises(ads.Unknown):
                        self.fp(changed)
                else:
                    self.assertFalse(ads.same_content(reference, self.fp(changed)))
        reordered = copy.deepcopy(original)
        reordered['packets'] = [reordered['packets'][1], reordered['packets'][0], reordered['packets'][3], reordered['packets'][2]]
        for packet in reordered['packets']:
            packet['pts'] += 30000
        self.assertTrue(ads.same_content(reference, self.fp(reordered)))


if __name__ == '__main__':
    unittest.main()
