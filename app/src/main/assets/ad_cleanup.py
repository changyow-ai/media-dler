"""Remove only catalogued, fully fingerprinted ad payloads from supported MP4s.

The anchor is a search accelerator, never evidence of a match. A no-match result
means unchanged, not ad-free. Child tools intentionally inherit our process group.
"""
import argparse
from contextlib import contextmanager
from fractions import Fraction
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile


class CleanupError(Exception):
    pass


CHUNK_SIZE = 1024 * 1024
MAX_CANDIDATES = 128
MAX_MATCHES = 16
HEX_HASH = re.compile(r'[0-9a-f]{64}\Z')


def require(condition, message):
    if not condition:
        raise CleanupError(message)


def load_signatures(path):
    require(path.stat().st_size <= 65536, 'Signature catalog is too large')
    data = json.loads(path.read_text(encoding='utf-8'))
    require(isinstance(data, dict) and data.get('schema') == 1, 'Unsupported signature catalog')
    signatures = data.get('signatures')
    require(isinstance(signatures, list) and 0 < len(signatures) <= 8,
            'Invalid signature catalog')
    seen = set()
    for item in signatures:
        require(isinstance(item, dict), 'Invalid signature')
        length, offset = item.get('length'), item.get('anchor_offset')
        require(type(length) is int and 64 <= length <= 64 * CHUNK_SIZE and
                type(offset) is int and 0 <= offset <= length - 64,
                'Invalid signature bounds')
        require(isinstance(item.get('id'), str) and 0 < len(item['id']) <= 80 and
                isinstance(item.get('sha256'), str) and HEX_HASH.fullmatch(item['sha256']),
                'Invalid signature identity')
        anchor = item.get('anchor_hex')
        require(isinstance(anchor, str) and re.fullmatch(r'[0-9a-f]{128}', anchor),
                'Invalid signature anchor')
        identity = (length, item['sha256'])
        require(identity not in seen, 'Duplicate signature')
        seen.add(identity)
    return signatures


def find_matches(source, signatures):
    size = source.stat().st_size
    matches, candidates = [], 0
    # Separate handles keep hash reads from disturbing the bounded scan cursor.
    with source.open('rb') as scan, source.open('rb') as verify:
        for signature in signatures:
            scan.seek(0)
            anchor = bytes.fromhex(signature['anchor_hex'])
            carry, consumed = b'', 0
            while True:
                chunk = scan.read(CHUNK_SIZE)
                if not chunk:
                    break
                block = carry + chunk
                base = consumed - len(carry)
                index = block.find(anchor)
                while index >= 0:
                    candidates += 1
                    require(candidates <= MAX_CANDIDATES, 'Too many signature candidates')
                    start = base + index - signature['anchor_offset']
                    end = start + signature['length']
                    if 0 <= start < end <= size:
                        verify.seek(start)
                        digest, remaining = hashlib.sha256(), signature['length']
                        while remaining:
                            piece = verify.read(min(CHUNK_SIZE, remaining))
                            require(piece, 'Input changed while fingerprinting')
                            digest.update(piece)
                            remaining -= len(piece)
                        if digest.hexdigest() == signature['sha256']:
                            matches.append({'start': start, 'end': end})
                            require(len(matches) <= MAX_MATCHES, 'Too many verified ad matches')
                    index = block.find(anchor, index + 1)
                consumed += len(chunk)
                carry = block[-(len(anchor) - 1):]
    matches.sort(key=lambda item: item['start'])
    require(all(a['end'] <= b['start'] for a, b in zip(matches, matches[1:])),
            'Overlapping verified ad matches')
    return matches


def run_metadata(ffprobe, source):
    with tempfile.TemporaryFile() as stdout, tempfile.TemporaryFile() as stderr:
        process = subprocess.run([str(ffprobe), '-v', 'error', '-show_entries',
            'stream=index,codec_name,codec_type,time_base,start_time,duration:'
            'format=format_name,start_time,duration', '-of', 'json', str(source)],
            stdin=subprocess.DEVNULL, stdout=stdout, stderr=stderr)
        require(process.returncode == 0 and stderr.tell() == 0, 'Media metadata probe failed')
        require(stdout.tell() <= 65536, 'Media metadata is too large')
        stdout.seek(0)
        return json.load(stdout)


def validate_metadata(metadata):
    streams = metadata.get('streams', [])
    require(len(streams) == 2, 'Expected exactly one video and one audio stream')
    by_type = {stream.get('codec_type'): stream for stream in streams}
    require(set(by_type) == {'video', 'audio'} and
            by_type['video'].get('codec_name') == 'h264' and
            by_type['audio'].get('codec_name') == 'aac', 'Unsupported media streams')
    require('mp4' in metadata.get('format', {}).get('format_name', '').split(','),
            'Unsupported media container')
    seen = set()
    for stream in streams:
        index = stream.get('index')
        require(type(index) is int and index >= 0 and index not in seen, 'Invalid stream index')
        seen.add(index)
        try:
            time_base = Fraction(stream['time_base'])
        except (KeyError, ValueError, ZeroDivisionError):
            raise CleanupError('Invalid stream time base') from None
        require(0 < time_base <= 1, 'Invalid stream time base')
        stream['tb'] = time_base
    duration = number(metadata.get('format', {}).get('duration'))
    require(duration > 0, 'Invalid media duration')
    return by_type, duration


def number(value):
    try:
        return Fraction(str(value))
    except (ValueError, ZeroDivisionError):
        raise CleanupError('Invalid media timestamp') from None


@contextmanager
def packets(ffprobe, source, stream_index=None):
    args = [str(ffprobe), '-v', 'error']
    if stream_index is not None:
        args += ['-select_streams', str(stream_index)]
    args += ['-show_packets', '-show_data_hash', 'sha256', '-show_entries',
             'packet=stream_index,pos,size,pts,dts,duration,flags,data_hash',
             '-of', 'compact=p=0', str(source)]
    with tempfile.TemporaryFile() as stderr:
        process = subprocess.Popen(args, stdin=subprocess.DEVNULL,
                                   stdout=subprocess.PIPE, stderr=stderr)
        def records():
            while True:
                raw = process.stdout.readline(8193)
                if not raw:
                    break
                require(len(raw) <= 8192, 'Packet metadata line is too large')
                fields = dict(part.split('=', 1) for part in
                              raw.decode('ascii').strip().split('|') if '=' in part)
                # Some ffprobe versions emit side-data-only lines.
                if 'stream_index' not in fields:
                    continue
                try:
                    packet = {key: int(fields[key]) for key in
                              ('stream_index', 'pos', 'size', 'pts', 'dts', 'duration')}
                except (KeyError, ValueError):
                    raise CleanupError('Missing or invalid packet metadata') from None
                digest = fields.get('data_hash', '')
                require(digest.startswith('SHA256:') and HEX_HASH.fullmatch(digest[7:].lower()),
                        'Missing packet payload hash')
                packet['hash'] = bytes.fromhex(digest[7:])
                packet['keyframe'] = 'K' in fields.get('flags', '')
                yield packet
            require(process.wait() == 0 and stderr.tell() == 0, 'Media packet probe failed')
        try:
            yield records()
        finally:
            process.stdout.close()
            if process.poll() is None:
                process.kill()
            process.wait()


def discover_boundaries(ffprobe, source, streams, matches):
    video_index = streams['video']['index']
    known = {stream['index'] for stream in streams.values()}
    wanted = {item[key] for item in matches for key in ('start', 'end')}
    boundaries, last_dts = {}, {}
    size = source.stat().st_size
    with packets(ffprobe, source) as records:
        for packet in records:
            index, pos = packet['stream_index'], packet['pos']
            require(index in known and 0 <= pos < size and packet['size'] > 0 and
                    pos + packet['size'] <= size and packet['duration'] > 0,
                    'Invalid packet bounds or duration')
            require(index not in last_dts or packet['dts'] > last_dts[index],
                    'Input decode timestamps are not strictly increasing')
            last_dts[index] = packet['dts']
            for item in matches:
                require(not (pos < item['start'] < pos + packet['size']) and
                        not (pos < item['end'] < pos + packet['size']),
                        'Verified payload boundary splits a packet')
            if index == video_index and pos in wanted:
                require(pos not in boundaries and packet['keyframe'],
                        'Verified payload boundary is not a unique video keyframe')
                boundaries[pos] = packet
    require(set(boundaries) == wanted and set(last_dts) == known,
            'Verified payload lacks video keyframe boundaries')
    previous_pts_end = previous_dts_end = None
    for item in matches:
        first, after = boundaries[item['start']], boundaries[item['end']]
        require(after['pts'] > first['pts'] and after['dts'] > first['dts'] and
                (previous_pts_end is None or first['pts'] >= previous_pts_end) and
                (previous_dts_end is None or first['dts'] >= previous_dts_end),
                'Verified ad timestamps are not ordered')
        item['seconds'] = (after['pts'] - first['pts']) * streams['video']['tb']
        previous_pts_end, previous_dts_end = after['pts'], after['dts']


def filter_expression(matches):
    drop = '+'.join('gte(pos,{start})*lt(pos,{end})'.format(**item) for item in matches)
    offset = ''.join('-gte(POS,{})*({}/{})/TB'.format(
        item['end'], item['seconds'].numerator, item['seconds'].denominator) for item in matches)
    return "noise=amount=0:drop='{}',setts=pts='PTS{}':dts='DTS{}'".format(drop, offset, offset)


def remux(ffmpeg, source, target, streams, matches):
    bsf = filter_expression(matches)
    args = [str(ffmpeg), '-nostdin', '-hide_banner', '-loglevel', 'warning', '-y',
            '-i', str(source), '-map', '0:{}'.format(streams['video']['index']),
            '-map', '0:{}'.format(streams['audio']['index']), '-c', 'copy',
            '-bsf:v', bsf, '-bsf:a', bsf, '-movflags', '+faststart', str(target)]
    with tempfile.TemporaryFile() as stderr:
        result = subprocess.run(args, stdin=subprocess.DEVNULL,
                                stdout=subprocess.DEVNULL, stderr=stderr)
        require(result.returncode == 0, 'Ad removal remux failed')
        require(stderr.tell() == 0, 'Ad removal remux emitted warnings')
    require(target.stat().st_size > 0, 'Ad removal produced an empty output')


def retained(records, matches):
    for packet in records:
        pos = packet['pos']
        if any(item['start'] <= pos < item['end'] for item in matches):
            continue
        shift = sum((item['seconds'] for item in matches if pos >= item['end']), Fraction())
        yield packet, shift


def validate_output(ffprobe, source, target, source_streams, source_duration, matches):
    output_streams, output_duration = validate_metadata(run_metadata(ffprobe, target))
    total = sum((item['seconds'] for item in matches), Fraction())
    require(abs(output_duration - (source_duration - total)) <= Fraction(1, 10),
            'Output duration does not match verified removal')
    output_size = target.stat().st_size
    ends, starts = {}, {}
    original_ends, original_starts = {}, {}
    for kind in ('video', 'audio'):
        before, after = source_streams[kind], output_streams[kind]
        expected_hash, actual_hash = hashlib.sha256(), hashlib.sha256()
        expected_count = actual_count = 0
        last_dts = None
        with packets(ffprobe, source, before['index']) as original, \
                packets(ffprobe, target, after['index']) as cleaned:
            expected = retained(original, matches)
            while True:
                pair, actual = next(expected, None), next(cleaned, None)
                require((pair is None) == (actual is None), 'Output packet count changed')
                if pair is None:
                    break
                packet, shift = pair
                expected_count += 1
                actual_count += 1
                expected_hash.update(packet['hash'])
                actual_hash.update(actual['hash'])
                require(packet['hash'] == actual['hash'], 'Output packet payload changed')
                require(actual['stream_index'] == after['index'] and actual['duration'] > 0 and
                        0 <= actual['pos'] < output_size and actual['size'] > 0 and
                        actual['pos'] + actual['size'] <= output_size,
                        'Invalid output packet')
                pts = packet['pts'] * before['tb'] - shift
                dts = packet['dts'] * before['tb'] - shift
                actual_pts, actual_dts = actual['pts'] * after['tb'], actual['dts'] * after['tb']
                tolerance = max(Fraction(1, 1000), 2 * before['tb'], 2 * after['tb'])
                require(abs(actual_pts - pts) <= tolerance and abs(actual_dts - dts) <= tolerance,
                        'Output packet timestamp changed unexpectedly')
                require(last_dts is None or actual_dts > last_dts,
                        'Output decode timestamps are not strictly increasing')
                last_dts = actual_dts
                starts[kind] = min(starts.get(kind, actual_pts), actual_pts)
                ends[kind] = max(ends.get(kind, actual_pts),
                                 actual_pts + actual['duration'] * after['tb'])
                source_pts = packet['pts'] * before['tb']
                original_starts[kind] = min(original_starts.get(kind, source_pts), source_pts)
                original_ends[kind] = max(original_ends.get(kind, source_pts),
                                          source_pts + packet['duration'] * before['tb'])
        require(expected_count > 0 and expected_count == actual_count and
                expected_hash.digest() == actual_hash.digest(), 'Output stream verification failed')
    require(abs(output_duration - max(ends.values())) <= Fraction(1, 10),
            'Output container end does not match packet end')
    for actual, original in ((ends, original_ends), (starts, original_starts)):
        require(abs(actual['video'] - actual['audio']) <=
                max(Fraction(1, 4), abs(original['video'] - original['audio']) + Fraction(1, 20)),
                'Output audio and video timing diverged')


def publish_owned_file(temporary, destination):
    # The caller owns an exclusive per-run directory. Android's bundled Python
    # omits os.link; same-directory rename still publishes a complete file.
    require(not os.path.lexists(destination), 'Refusing to overwrite output or result')
    os.rename(temporary, destination)


def atomic_result(destination, data):
    fd, name = tempfile.mkstemp(prefix='.ad-result-', dir=destination.parent)
    temporary = Path(name)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as stream:
            json.dump(data, stream, separators=(',', ':'), allow_nan=False)
            stream.flush()
            os.fsync(stream.fileno())
        publish_owned_file(temporary, destination)
    finally:
        temporary.unlink(missing_ok=True)


def cleanup(source, output, result, signatures, ffmpeg, ffprobe):
    paths = [Path(path) for path in (source, output, result, signatures, ffmpeg, ffprobe)]
    require(all(path.is_absolute() for path in paths), 'All paths must be absolute')
    source, output, result, signatures, ffmpeg, ffprobe = paths
    output, result = [path.parent.resolve() / path.name for path in (output, result)]
    source, signatures, ffmpeg, ffprobe = [path.resolve() for path in
                                           (source, signatures, ffmpeg, ffprobe)]
    require(source.is_file(), 'Input file is missing')
    require(len({source, output, result, signatures, ffmpeg, ffprobe}) == 6,
            'Input, output and tool paths must be distinct')
    require(not os.path.lexists(output) and not os.path.lexists(result),
            'Refusing to overwrite output or result')
    initial_stat = source.stat()
    identity = (initial_stat.st_dev, initial_stat.st_ino, initial_stat.st_size, initial_stat.st_mtime_ns)
    print('AD_CLEANUP_STAGE scan', flush=True)
    matches = find_matches(source, load_signatures(signatures))
    partial = None
    published_identity = None
    try:
        data = {'schema': 1, 'status': 'unchanged', 'removed_ads': 0, 'removed_seconds': 0}
        if matches:
            print('AD_CLEANUP_STAGE probe', flush=True)
            streams, duration = validate_metadata(run_metadata(ffprobe, source))
            discover_boundaries(ffprobe, source, streams, matches)
            fd, name = tempfile.mkstemp(prefix='.ad-output-', suffix='.mp4', dir=output.parent)
            os.close(fd)
            partial = Path(name)
            print('AD_CLEANUP_STAGE remux', flush=True)
            remux(ffmpeg, source, partial, streams, matches)
            print('AD_CLEANUP_STAGE validate', flush=True)
            validate_output(ffprobe, source, partial, streams, duration, matches)
            data.update(status='cleaned', removed_ads=len(matches),
                        removed_seconds=float(sum((item['seconds'] for item in matches), Fraction())),
                        output_size=partial.stat().st_size)
        final_stat = source.stat()
        require(identity == (final_stat.st_dev, final_stat.st_ino, final_stat.st_size,
                             final_stat.st_mtime_ns), 'Input changed during ad removal')
        if partial is not None:
            with partial.open('rb') as stream:
                os.fsync(stream.fileno())
            stat = partial.stat()
            publish_owned_file(partial, output)
            published_identity = (stat.st_dev, stat.st_ino)
        atomic_result(result, data)
        return data
    except BaseException:
        if published_identity is not None and output.exists():
            stat = output.lstat()
            if (stat.st_dev, stat.st_ino) == published_identity:
                output.unlink()
        raise
    finally:
        if partial is not None:
            partial.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('input', 'output', 'result', 'signatures', 'ffmpeg', 'ffprobe'):
        parser.add_argument('--' + name, required=True)
    args = parser.parse_args()
    try:
        cleanup(args.input, args.output, args.result, args.signatures, args.ffmpeg, args.ffprobe)
    except (CleanupError, OSError, ValueError, TypeError, KeyError) as error:
        # Tool stderr and source URLs are deliberately not copied into diagnostics.
        message = str(error) if isinstance(error, CleanupError) else 'Ad removal could not complete'
        print(message, file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
