#!/usr/bin/env python3
"""Per-process native HLS checkpoint adapter (yt-dlp 2025.11.12 and 2026.08.19).

The sidecar is authoritative. Native .ytdl can advance even when append fails.
Every restart rolls back the last committed fragment before native HLS starts.
"""
import argparse
import inspect
import json
import math
import os
import shutil
from pathlib import Path
import re
import sys
import tempfile
from urllib.parse import unquote, urljoin, urlsplit


class ResumeError(RuntimeError):
    pass


def check_engine_version(version):
    # The archive can be updated independently of the app. Private downloader
    # semantics must be exercised before a new version may truncate old output.
    if version not in {'2025.11.12', '2026.08.19'}:
        raise ResumeError('此 yt-dlp 版本尚未驗證 HLS 續傳（{}），已保留暫存；請使用支援此引擎的 App 版本'.format(version))


def atomic_json(path, value):
    path = Path(path)
    descriptor, temporary = tempfile.mkstemp(prefix=path.name + '.', suffix='.tmp', dir=path.parent)
    try:
        with os.fdopen(descriptor, 'w', encoding='utf-8') as stream:
            json.dump(value, stream, separators=(',', ':'), ensure_ascii=False)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def validate_manifest(manifest):
    """Accept complete unencrypted media playlists with one URI per EXTINF."""
    if not isinstance(manifest, str):
        raise ResumeError('Invalid HLS snapshot')
    lines = [line.strip() for line in manifest.splitlines() if line.strip()]
    if not lines or lines[0] != '#EXTM3U':
        raise ResumeError('Invalid HLS snapshot header')
    count, pending, ended = 0, False, False
    safe_tags = {'#EXTM3U', '#EXT-X-VERSION', '#EXT-X-TARGETDURATION',
                 '#EXT-X-MEDIA-SEQUENCE', '#EXT-X-DISCONTINUITY',
                 '#EXT-X-DISCONTINUITY-SEQUENCE', '#EXT-X-INDEPENDENT-SEGMENTS',
                 '#EXT-X-ALLOW-CACHE'}
    for line in lines[1:]:
        if line.startswith('#EXTINF:'):
            try:
                duration = float(line[8:].split(',', 1)[0])
            except ValueError:
                raise ResumeError('Invalid HLS segment duration') from None
            if pending or ended or not math.isfinite(duration) or duration <= 0:
                raise ResumeError('Invalid HLS segment order')
            pending = True
        elif line == '#EXT-X-ENDLIST':
            if pending or ended:
                raise ResumeError('Invalid HLS end marker')
            ended = True
        elif line.startswith('#EXT-X-KEY:'):
            if line != '#EXT-X-KEY:METHOD=NONE':
                raise ResumeError('Encrypted HLS is not checkpoint compatible')
        elif line.startswith('#EXT-X-PLAYLIST-TYPE:'):
            if line != '#EXT-X-PLAYLIST-TYPE:VOD':
                raise ResumeError('Only complete VOD playlists are checkpoint compatible')
        elif line.startswith('#'):
            tag = line.split(':', 1)[0]
            if tag.startswith(('#EXT', '#ANVATO', '#UPLYNK')) and tag not in safe_tags:
                raise ResumeError('Unsupported HLS snapshot feature')
        else:
            try:
                uri = urlsplit(line)
                valid = (uri.scheme in ('', 'http', 'https') and uri.username is None
                         and '#' not in line and not any(char.isspace() for char in line)
                         and unquote(uri.path).lower().endswith('.ts')
                         and (not uri.scheme or bool(uri.hostname))
                         and (not uri.netloc or bool(uri.hostname)))
            except ValueError:
                valid = False
            if ended or not pending or not valid:
                raise ResumeError('Invalid HLS media URI')
            count += 1
            pending = False
    if not ended or pending or not count:
        raise ResumeError('HLS snapshot must be a complete media playlist')
    return count


class CheckpointStore:
    def __init__(self, spec_path):
        spec_path = Path(spec_path).absolute()
        if spec_path.parent.name != '.resume':
            raise ResumeError('Session must be inside the download resume directory')
        self.root = spec_path.parent.parent.resolve()
        self.path = spec_path.parent / 'checkpoint.json'
        try:
            spec = json.loads(spec_path.read_text(encoding='utf-8'))
        except (OSError, ValueError):
            raise ResumeError('Cannot read HLS resume session') from None
        if (not isinstance(spec, dict) or type(spec.get('schema')) is not int or spec['schema'] != 1
                or not isinstance(spec.get('identity'), str)
                or not re.fullmatch(r'[0-9a-fA-F]{64}', spec['identity'])):
            raise ResumeError('Invalid HLS resume session metadata')
        self.identity = spec['identity']
        self.url = spec.get('url')
        if not isinstance(self.url, str) or urlsplit(self.url).scheme not in ('http', 'https') or not urlsplit(self.url).hostname or urlsplit(self.url).username is not None or '#' in self.url:
            raise ResumeError('Invalid HLS child URL')
        self.manifest = spec.get('manifest')
        self.fragment_count = validate_manifest(self.manifest)
        for line in self.manifest.splitlines():
            if line.strip() and not line.strip().startswith('#'):
                parsed = urlsplit(urljoin(self.url, line.strip()))
                if (parsed.scheme not in ('http', 'https') or not parsed.hostname
                        or parsed.username is not None or parsed.fragment):
                    raise ResumeError('Invalid HLS segment URL')
        self.fragment_urls = [urljoin(self.url, line.strip()) for line in self.manifest.splitlines()
                              if line.strip() and not line.strip().startswith('#')]
        self.cached_segments = {}
        if spec.get('ad_plan') is not None:
            self._load_ad_plan(spec['ad_plan'], spec_path.parent)
        self.index = None
        self.end = None

    def _load_ad_plan(self, value, resume_root):
        if not isinstance(value, str):
            raise ResumeError('Invalid frozen HLS ad plan path')
        path = Path(value)
        if (not path.is_absolute() or path.parent != resume_root or path.is_symlink()
                or resume_root.is_symlink()):
            raise ResumeError('HLS ad plan must belong to this resume directory')
        # Optional dependency: ordinary checkpoint sessions keep their old path.
        sys.path.insert(0, str(Path(__file__).resolve().parent))
        from hls_ad_filter import FilterError, MAX_TOTAL_BYTES, cache_path, validate_plan
        try:
            plan = validate_plan(json.loads(path.read_text(encoding='utf-8')))
            if plan['identity'] != self.identity or plan['manifest'] != self.manifest:
                raise ResumeError('Frozen HLS ad plan does not match this session')
            expected = set(self.fragment_urls)
            for entry in plan['cached_segments']:
                cache_path(resume_root, entry)
                if entry['url'] not in expected or entry['url'] in self.cached_segments:
                    raise ResumeError('Unexpected frozen HLS cached URL')
                self.cached_segments[entry['url']] = entry
            if sum(entry['size'] for entry in self.cached_segments.values()) > MAX_TOTAL_BYTES:
                raise ResumeError('Frozen HLS cache exceeds byte budget')
        except (OSError, ValueError, KeyError, TypeError, FilterError):
            raise ResumeError('Invalid frozen HLS ad plan or cache metadata') from None
        self.cache_root = resume_root

    def copy_cached_fragment(self, ctx, frag_url):
        if not self.cached_segments:
            return False
        index = ctx.get('fragment_index')
        if (type(index) is not int or not 1 <= index <= len(self.fragment_urls)
                or self.fragment_urls[index - 1] != frag_url):
            raise ResumeError('Cached HLS fragment index does not match frozen plan')
        entry = self.cached_segments.get(frag_url)
        if entry is None:
            return False
        from hls_ad_filter import FilterError, valid_cached
        try:
            source = valid_cached(self.cache_root, entry)
        except FilterError:
            raise ResumeError('Unsafe frozen HLS cache path') from None
        # Cache is optional, whereas the frozen skip set and checkpoint identity
        # are mandatory. A missing/corrupt retained segment uses normal download.
        if source is None:
            return False
        destination = Path('%s-Frag%d' % (ctx['tmpfilename'], index))
        if destination.parent.resolve() != self.root or destination.is_symlink():
            raise ResumeError('Unsafe cached HLS fragment output')
        descriptor, temporary = tempfile.mkstemp(prefix=destination.name + '.cache-', dir=self.root)
        try:
            with os.fdopen(descriptor, 'wb') as target, source.open('rb') as origin:
                shutil.copyfileobj(origin, target)
                target.flush()
                os.fsync(target.fileno())
            os.replace(temporary, destination)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
        # yt-dlp owns and deletes this disposable copy, never the durable cache.
        ctx['fragment_filename_sanitized'] = str(destination)
        ctx['frag_resume_len'] = 0
        return True

    def _record(self, index, start, end):
        return {'schema': 1, 'identity': self.identity, 'filename': self.filename.name,
                'index': index, 'start_offset': start, 'end_offset': end}

    def prepare(self, filename):
        self.filename = Path(filename).absolute()
        if self.filename.parent.resolve() != self.root or self.filename.name in ('', '-', '.', '..'):
            raise ResumeError('HLS output must belong to this download directory')
        self.part = Path(str(self.filename) + '.part')
        journal = Path(str(self.filename) + '.ytdl')
        if any(path.is_symlink() for path in (self.filename, self.part, self.path, journal)):
            raise ResumeError('HLS resume files must not be symbolic links')
        if self.filename.exists():
            if self.part.exists():
                raise ResumeError('Both finalized and partial HLS output exist')
            return None
        existed = self.path.exists()
        if existed:
            try:
                record = json.loads(self.path.read_text(encoding='utf-8'))
            except (OSError, ValueError):
                raise ResumeError('Cannot read authoritative HLS checkpoint') from None
            if (not isinstance(record, dict) or type(record.get('schema')) is not int or record['schema'] != 1
                    or record.get('identity') != self.identity or record.get('filename') != self.filename.name
                    or any(type(record.get(key)) is not int for key in ('index', 'start_offset', 'end_offset'))):
                raise ResumeError('HLS checkpoint does not match this session')
            index, start, end = (record[key] for key in ('index', 'start_offset', 'end_offset'))
            if not (0 <= index <= self.fragment_count and 0 <= start <= end
                    and ((index == 0 and start == end == 0) or (index > 0 and start < end))):
                raise ResumeError('Invalid HLS checkpoint offsets')
            size = self.part.stat().st_size if self.part.exists() else 0
            if size < start or (index > 0 and not self.part.exists()):
                raise ResumeError('HLS partial file is shorter than its checkpoint')
        else:
            if self.part.exists() and self.part.stat().st_size:
                raise ResumeError('HLS partial file has no authoritative checkpoint')
            index = start = end = 0
            atomic_json(self.path, self._record(0, 0, 0))
        # Retain the prior sidecar until another full append commits. This makes
        # recovery safe after a kill between truncation and journal replacement.
        if self.part.exists():
            with self.part.open('r+b') as stream:
                stream.truncate(start)
                stream.flush()
                os.fsync(stream.fileno())
        self.index, self.end = max(0, index - 1), start
        atomic_json(journal, {'downloader': {'current_fragment': {'index': self.index}, 'extra_state': {}}})
        # Exact filename matching avoids glob metacharacters and unrelated files.
        pattern = re.compile(re.escape(self.part.name) + r'-Frag[0-9]+(?:\.part)?(?:\.ytdl)?$')
        for path in self.root.iterdir():
            if pattern.fullmatch(path.name) and (path.is_file() or path.is_symlink()):
                path.unlink()
        if existed and index:
            print('media-dler-resume:{}'.format(index), flush=True)
        return self.index

    def append(self, ctx, content, original):
        if (self.index is None or ctx.get('fragment_index') != self.index + 1
                or not content or self.index >= self.fragment_count):
            raise ResumeError('Unexpected HLS fragment sequence')
        stream = ctx['dest_stream']
        start = stream.tell()
        if start != self.end:
            raise ResumeError('HLS append offset does not match checkpoint')
        result = original(ctx, content)
        end = stream.tell()
        if end - start != len(content):
            raise ResumeError('Incomplete HLS fragment append')
        stream.flush()
        os.fsync(stream.fileno())
        if os.fstat(stream.fileno()).st_size != end:
            raise ResumeError('HLS output size does not match append')
        index = self.index + 1
        atomic_json(self.path, self._record(index, start, end))
        self.index, self.end = index, end
        return result


def check_private_api(hls_class, fragment_class):
    """Fail closed on a different downloader protocol before modifying files."""
    if (hls_class._append_fragment is not fragment_class._append_fragment
            or hls_class._download_fragment is not fragment_class._download_fragment):
        raise ResumeError('Bundled yt-dlp overrides the HLS fragment protocol')
    checks = [
        (fragment_class._start_frag_download, ('self', 'ctx', 'info_dict'),
         ('fragment_index', 'complete_frags_downloaded_bytes', 'total_frags'), ('add_progress_hook', 'ProgressCalculator')),
        (fragment_class._hook_progress, ('self', 'status', 'info_dict'), ('info_dict',), ('_progress_hooks',)),
        (fragment_class._download_fragment, ('self', 'ctx', 'frag_url', 'info_dict', 'headers', 'request_data'),
         ('tmpfilename', 'fragment_index', 'fragment_filename_sanitized'), ('download', 'temp_name', 'filesize_or_none')),
        (hls_class.real_download, ('self', 'filename', 'info_dict'),
         ('hls_media_playlist_data', 'format_index', 'fragment_index'),
         ('_prepare_and_start_frag_download', 'download_and_append_fragments', 'urljoin')),
        (fragment_class._append_fragment, ('self', 'ctx', 'frag_content'),
         ('dest_stream', 'fragment_filename_sanitized'), ('write', 'flush', '_write_ytdl_file')),
        (fragment_class._prepare_frag_download, ('self', 'ctx'),
         ('tmpfilename', 'fragment_index', 'dest_stream'), ('temp_name', '_read_ytdl_file')),
        (fragment_class._write_ytdl_file, ('self', 'ctx'),
         ('downloader', 'current_fragment', 'index'), ('ytdl_filename',)),
    ]
    def constant_values(values):
        # Python versions encode constant dictionary keys either separately or
        # inside a tuple (BUILD_CONST_KEY_MAP). Accept both compiler layouts.
        for value in values:
            if isinstance(value, tuple):
                yield from constant_values(value)
            else:
                yield value

    for function, parameters, constants, names in checks:
        code = getattr(function, '__code__', None)
        if (code is None or tuple(inspect.signature(function).parameters) != parameters
                or not all(value in tuple(constant_values(code.co_consts)) for value in constants)
                or not all(value in code.co_names for value in names)):
            raise ResumeError('Bundled yt-dlp HLS API is incompatible with checkpoint recovery')


def install_adapter(store):
    from yt_dlp.version import __version__
    check_engine_version(__version__)
    from yt_dlp.downloader.hls import HlsFD
    from yt_dlp.downloader.fragment import FragmentFD
    check_private_api(HlsFD, FragmentFD)
    original_download = HlsFD.real_download
    original_append = HlsFD._append_fragment
    original_fragment = HlsFD._download_fragment

    def real_download(self, filename, info_dict):
        if (info_dict.get('url') != store.url or info_dict.get('format_index') is not None
                or info_dict.get('is_live') or info_dict.get('ext') == 'vtt'
                or info_dict.get('hls_aes') or info_dict.get('extra_param_to_segment_url')
                or info_dict.get('extra_param_to_key_url')):
            raise ResumeError('Selected HLS stream does not match the saved session')
        if self.temp_name(filename) != str(filename) + '.part':
            raise ResumeError('HLS checkpoint recovery requires partial output files')
        self.params.update({'concurrent_fragment_downloads': 1, 'continuedl': True,
                            'skip_unavailable_fragments': False, '_no_ytdl_file': False,
                            'external_downloader': {}, 'test': False})
        if store.prepare(filename) is None:
            return True
        self._media_dler_checkpoint = store
        info_dict['url'] = store.url
        info_dict['hls_media_playlist_data'] = store.manifest
        return original_download(self, filename, info_dict)

    def append_fragment(self, ctx, content):
        active = getattr(self, '_media_dler_checkpoint', None)
        if active is not store or Path(ctx['tmpfilename']).absolute() != store.part:
            raise ResumeError('Unexpected HLS output context')
        return store.append(ctx, content, lambda context, data: original_append(self, context, data))

    def download_fragment(self, ctx, frag_url, info_dict, headers=None, request_data=None):
        active = getattr(self, '_media_dler_checkpoint', None)
        if (active is store and Path(ctx['tmpfilename']).absolute() == store.part
                and request_data is None and store.copy_cached_fragment(ctx, frag_url)):
            # Native HLS keeps a second fragment index inside its HTTP progress
            # closure. Advance that state exactly once, just as a real download
            # completion does, before any later cache/network fragment arrives.
            intended = ctx['fragment_index']
            filename = ctx['fragment_filename_sanitized']
            size = os.path.getsize(filename)
            fragment_info = {'url': frag_url, 'http_headers': headers or info_dict.get('http_headers'),
                             'request_data': None, 'ctx_id': ctx.get('ctx_id'), 'frag_resume_len': 0}
            ctx['dl']._hook_progress({'status': 'finished', 'downloaded_bytes': size, 'total_bytes': size,
                                      'filename': filename, 'ctx_id': ctx.get('ctx_id')}, fragment_info)
            if ctx['fragment_index'] != intended:
                raise ResumeError('Cached HLS completion changed the expected fragment index')
            return True
        return original_fragment(self, ctx, frag_url, info_dict, headers, request_data)

    HlsFD.real_download = real_download
    HlsFD._append_fragment = append_fragment
    HlsFD._download_fragment = download_fragment


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument('--spec', required=True)
    parser.add_argument('--ytdlp', required=True)
    args, cli_args = parser.parse_known_args(argv)
    if cli_args[:1] == ['--']:
        cli_args = cli_args[1:]
    archive = Path(args.ytdlp).absolute()
    if not archive.is_file():
        raise ResumeError('Bundled yt-dlp archive is unavailable')
    store = CheckpointStore(args.spec)
    sys.path.insert(0, str(archive))
    import yt_dlp
    # Ensure this process never silently falls back to a host installation.
    if not str(yt_dlp.__file__).startswith(str(archive) + os.sep):
        raise ResumeError('yt-dlp was not imported from the bundled archive')
    install_adapter(store)
    return yt_dlp.main([*cli_args, '--hls-prefer-native', '--downloader', 'm3u8:native',
                        '--concurrent-fragments', '1', '--abort-on-unavailable-fragments', '--continue'])


if __name__ == '__main__':
    try:
        main()
    except ResumeError as error:
        print('ERROR: media-dler HLS resume: {}'.format(error), file=sys.stderr, flush=True)
        sys.exit(1)
