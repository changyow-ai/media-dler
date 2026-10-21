"""Bounded, optional validation of committed native HLS bytes.

Notifications contain cumulative offsets, fragment counts and retained seconds.
A single decoder retains demuxer/codec context until finish closes its input.
Any uncertainty returns unavailable so callers can use their ordinary validator.
"""
import hashlib
from functools import lru_cache
from fractions import Fraction
import math
import os
import select
import subprocess
import threading
import time


CHUNK_BYTES = 64 * 1024
BATCH_SECONDS = 30
BATCH_SEGMENTS = 12
BATCH_BYTES = 8 * 1024 * 1024
IO_TIMEOUT = 120
FINISH_TIMEOUT = 3600
DIAGNOSTIC_BYTES = 4096


@lru_cache(maxsize=8)
def _file_hash(path, size, mtime_ns):
    digest = hashlib.sha256()
    with open(path, 'rb') as source:
        while True:
            data = source.read(CHUNK_BYTES)
            if not data:
                return digest.hexdigest()
            digest.update(data)


def proof_identity(ffmpeg):
    """Bind proofs to this implementation and the exact native executable."""
    result = {}
    for key, path in (('validator_sha256', __file__), ('ffmpeg_sha256', ffmpeg)):
        path = os.path.realpath(path)
        stat = os.stat(path)
        result[key] = _file_hash(path, stat.st_size, stat.st_mtime_ns)
    return result


def _command(ffmpeg, source, audio_fd, timing_fd, decode):
    command = [str(ffmpeg), '-nostdin', '-v', 'error', '-xerror',
               '-threads', '2', '-filter_threads', '2', '-i', str(source)]
    if decode:
        command += ['-map', '0:v', '-map', '0:a', '-threads', '2', '-vsync', '0',
                    '-af', 'asetpts=N/SR/TB', '-f', 'null', os.devnull]
    command += ['-map', '0:v', '-c:v', 'copy', '-bsf:v',
                'h264_mp4toannexb,h264_metadata=aud=remove', '-f', 'h264', 'pipe:1',
                '-map', '0:a', '-c:a', 'copy', '-f', 'adts', 'pipe:{}'.format(audio_fd),
                '-map', '0:v:0', '-c:v', 'copy', '-f', 'framecrc', 'pipe:{}'.format(timing_fd)]
    return command


class _Outputs:
    """Drain every child output concurrently, retaining hashes and bounded errors."""
    def __init__(self, ffmpeg, source, decode):
        self.process = None
        self.threads = []
        self.streams = []
        self.hashes = {}
        self.eof = set()
        self.errors = []
        self.diagnostics = bytearray()
        self.had_diagnostics = False
        read_fd, write_fd = os.pipe()
        timing_read, timing_write = os.pipe()
        try:
            self.process = subprocess.Popen(_command(ffmpeg, source, write_fd, timing_write, decode),
                stdin=subprocess.PIPE if source == 'pipe:0' else subprocess.DEVNULL,
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, pass_fds=(write_fd, timing_write), bufsize=0)
        except BaseException:
            os.close(read_fd)
            os.close(timing_read)
            raise
        finally:
            os.close(write_fd)
            os.close(timing_write)
        self.streams = [self.process.stdout, os.fdopen(read_fd, 'rb', buffering=0), self.process.stderr,
                        os.fdopen(timing_read, 'rb', buffering=CHUNK_BYTES)]
        for name, stream in zip(('video', 'audio', 'stderr', 'video_timing'), self.streams):
            target = self._drain_timing if name == 'video_timing' else self._drain
            thread = threading.Thread(target=target, args=(name, stream),
                                      name='hls-validation-' + name, daemon=True)
            self.threads.append(thread)
            thread.start()

    def _drain(self, name, stream):
        digest, size = hashlib.sha256(), 0
        try:
            while True:
                data = stream.read(CHUNK_BYTES)
                if not data:
                    self.eof.add(name)
                    break
                if name == 'stderr':
                    self.had_diagnostics = True
                    remaining = DIAGNOSTIC_BYTES - len(self.diagnostics)
                    self.diagnostics.extend(data[:max(0, remaining)])
                else:
                    digest.update(data)
                    size += len(data)
            if name != 'stderr':
                self.hashes[name] = {'bytes': size, 'sha256': digest.hexdigest()}
        except (OSError, ValueError) as exc:
            self.errors.append(str(exc))

    def _drain_timing(self, name, stream):
        digest, count = hashlib.sha256(), 0
        timebase, first_dts = None, None
        failed = False
        try:
            while True:
                # FFmpeg framecrc emits one short header/packet record per line.
                # A malformed record disables proof, but still drain to EOF.
                line = stream.readline(4097)
                if not line:
                    self.eof.add(name)
                    break
                if failed:
                    continue
                try:
                    if len(line) > 4096 or not line.endswith(b'\n'):
                        raise ValueError('oversize_or_incomplete_video_timing_record')
                    line = line.strip()
                    if line.startswith(b'#tb 0:'):
                        if timebase is not None:
                            raise ValueError('duplicate_video_timebase')
                        numerator, denominator = line.split(b':', 1)[1].split(b'/')
                        timebase = Fraction(int(numerator), int(denominator))
                        if timebase <= 0:
                            raise ValueError('invalid_video_timebase')
                    elif line and not line.startswith(b'#'):
                        fields = line.split(b',')
                        if timebase is None or len(fields) < 6 or int(fields[0]) != 0:
                            raise ValueError('invalid_video_timing_record')
                        dts, pts = int(fields[1]), int(fields[2])
                        if first_dts is None:
                            first_dts = dts
                        dts = (dts - first_dts) * timebase
                        pts = (pts - first_dts) * timebase
                        record = '{}/{},{}/{}\n'.format(
                            dts.numerator, dts.denominator, pts.numerator, pts.denominator)
                        digest.update(record.encode('ascii'))
                        count += 1
                except (ValueError, ZeroDivisionError, OverflowError) as exc:
                    self.errors.append('video_timing:' + str(exc)[:DIAGNOSTIC_BYTES])
                    failed = True
            if not failed and count:
                self.hashes[name] = {'packets': count, 'sha256': digest.hexdigest()}
        except (OSError, ValueError) as exc:
            self.errors.append(str(exc))

    def result(self, timeout):
        self.process.wait(timeout=timeout)
        deadline = time.monotonic() + IO_TIMEOUT
        for thread in self.threads:
            thread.join(max(0, deadline - time.monotonic()))
        if (self.process.returncode != 0 or self.had_diagnostics or self.errors
                or self.eof != {'video', 'audio', 'stderr', 'video_timing'}
                or any(thread.is_alive() for thread in self.threads)
                or any(not self.hashes.get(name, {}).get('bytes') for name in ('video', 'audio'))
                or not self.hashes.get('video_timing', {}).get('packets')):
            diagnostic = self.diagnostics.decode('utf-8', 'replace') or ';'.join(self.errors)
            raise ValueError('ffmpeg_validation_failed:' + diagnostic[:DIAGNOSTIC_BYTES])
        return self.hashes

    def close(self):
        if self.process.poll() is None:
            self.process.kill()
        self.process.wait()
        if self.process.stdin:
            self.process.stdin.close()
        # Killing the only writer wakes all blocking readers. Join before closing
        # their descriptors, avoiding descriptor reuse races with reader threads.
        for thread in self.threads:
            thread.join()
        for stream in self.streams:
            stream.close()


def fingerprint_file(path, ffmpeg, audio_only=False):
    """Hash canonical encoded H264/AAC streams, failing closed on any diagnostic."""
    if audio_only:
        return None
    outputs = None
    try:
        before = os.stat(path)
        outputs = _Outputs(ffmpeg, str(path), False)
        result = outputs.result(FINISH_TIMEOUT)
        after = os.stat(path)
        if (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) != (
                after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns):
            return None
        return result
    except (OSError, ValueError, subprocess.SubprocessError):
        return None
    finally:
        if outputs:
            outputs.close()


class DownloadValidator:
    """One independent committed-prefix reader and one continuous FFmpeg child.

    notify never waits for decoding. A resumed first notification replays byte 0,
    and only the latest committed watermark is retained while decoding catches up.
    """
    def __init__(self, ffmpeg, identity, token, audio_only=False):
        self.ffmpeg = ffmpeg
        self.identity, self.token = identity, token
        self.audio_only = audio_only
        self._condition = threading.Condition()
        self._path = None
        self._latest = (0, 0, 0.0)
        self._fed = (0, 0, 0.0)
        self._finishing = False
        self._closed = False
        self._error = 'audio_only_unsupported' if audio_only else None
        self._outputs = None
        self._source = None
        self._thread = None
        self._source_hash = hashlib.sha256()
        self._source_bytes = 0
        self._batch_count = 0
        self._first_batch_segments = None
        self._tail_seconds = 0.0
        self._started = time.monotonic()
        self._eof = False
        self._fingerprints = None
        self._receipt = None

    def notify(self, path, end_offset, fragment_index, media_seconds):
        try:
            path = os.path.abspath(path)
            if (type(end_offset) is not int or type(fragment_index) is not int
                    or end_offset <= 0 or fragment_index <= 0
                    or not isinstance(media_seconds, (float, int))
                    or not math.isfinite(media_seconds) or media_seconds < 0):
                raise ValueError('invalid_committed_watermark')
            with self._condition:
                if self._closed or self._finishing or self._error:
                    return
                previous = self._latest
                if (self._path not in (None, path) or end_offset < previous[0]
                        or fragment_index < previous[1] or media_seconds < previous[2]):
                    raise ValueError('non_monotonic_committed_watermark')
                if self._source is None:
                    # Own the inode before native download renames its .part file.
                    self._source = open(path, 'rb', buffering=0)
                self._path = path
                self._latest = (end_offset, fragment_index, float(media_seconds))
                if self._thread is None:
                    self._thread = threading.Thread(target=self._run, name='hls-validation-feed', daemon=True)
                    self._thread.start()
                self._condition.notify_all()
        except (OSError, ValueError, TypeError) as exc:
            with self._condition:
                self._error = str(exc)
                self._condition.notify_all()

    def _ready(self):
        return (self._latest[0] - self._fed[0] >= BATCH_BYTES
                or self._latest[1] - self._fed[1] >= BATCH_SEGMENTS
                or self._latest[2] - self._fed[2] >= BATCH_SECONDS)

    def _write(self, data):
        descriptor = self._outputs.process.stdin.fileno()
        pending = memoryview(data)
        deadline = time.monotonic() + IO_TIMEOUT
        while pending:
            if self._closed or self._error:
                raise ValueError('validation_cancelled')
            if time.monotonic() >= deadline:
                raise ValueError('ffmpeg_input_timeout')
            if not select.select([], [descriptor], [], min(0.25, max(0, deadline - time.monotonic())))[1]:
                continue
            try:
                written = os.write(descriptor, pending)
            except BlockingIOError:
                continue
            if written <= 0:
                raise ValueError('ffmpeg_input_closed')
            pending = pending[written:]
            deadline = time.monotonic() + IO_TIMEOUT

    def _run(self):
        source = self._source
        try:
            while True:
                with self._condition:
                    self._condition.wait_for(lambda: self._closed or self._error or self._finishing or self._ready())
                    if self._closed or self._error:
                        return
                    target = self._latest
                    finishing = self._finishing
                if target[0] > self._fed[0]:
                    if self._outputs is None:
                        outputs = _Outputs(self.ffmpeg, 'pipe:0', True)
                        with self._condition:
                            self._outputs = outputs
                        os.set_blocking(outputs.process.stdin.fileno(), False)
                    remaining = target[0] - self._fed[0]
                    while remaining:
                        data = source.read(min(CHUNK_BYTES, remaining))
                        if not data:
                            raise ValueError('committed_prefix_truncated')
                        self._write(data)
                        self._source_hash.update(data)
                        self._source_bytes += len(data)
                        remaining -= len(data)
                    self._batch_count += 1
                    if self._first_batch_segments is None:
                        self._first_batch_segments = target[1]
                    if finishing:
                        self._tail_seconds = target[2] - self._fed[2]
                    self._fed = target
                if finishing:
                    if self._outputs is None:
                        raise ValueError('empty_committed_media')
                    self._outputs.process.stdin.close()
                    self._fingerprints = self._outputs.result(FINISH_TIMEOUT)
                    self._eof = True
                    return
        except (OSError, ValueError, subprocess.SubprocessError) as exc:
            self._error = str(exc)[:DIAGNOSTIC_BYTES]
        finally:
            if source:
                source.close()
            if self._outputs:
                self._outputs.close()

    def finish(self):
        with self._condition:
            if self._receipt is not None:
                return self._receipt
            self._finishing = True
            self._condition.notify_all()
        if self._thread:
            self._thread.join(FINISH_TIMEOUT + IO_TIMEOUT)
            if self._thread.is_alive():
                self._error = 'validation_finish_timeout'
                self.close()
        binding = {}
        try:
            binding = proof_identity(self.ffmpeg)
        except (OSError, ValueError) as exc:
            self._error = 'proof_identity_failed:' + str(exc)[:DIAGNOSTIC_BYTES]
        valid = self._eof and not self._error and not self._closed and self._fed == self._latest
        self._receipt = {'schema': 1, 'status': 'valid' if valid else 'unavailable',
            'identity': self.identity, 'token': self.token,
            'media_mode': 'audio' if self.audio_only else 'video',
            'source_bytes': self._source_bytes, 'source_sha256': self._source_hash.hexdigest(),
            'segments': self._fed[1], 'eof': bool(valid), 'full_decode': bool(valid),
            'fingerprints': self._fingerprints if valid else None,
            'batch_count': self._batch_count, 'first_batch_segments': self._first_batch_segments,
            'elapsed_seconds': round(time.monotonic() - self._started, 6),
            'tail_seconds': self._tail_seconds}
        self._receipt.update(binding)
        if not valid:
            self._receipt['reason'] = self._error or ('validation_closed' if self._closed else 'empty_committed_media')
        return self._receipt

    def close(self):
        with self._condition:
            self._closed = True
            self._condition.notify_all()
            outputs = self._outputs
        if outputs and outputs.process.poll() is None:
            try:
                outputs.process.kill()
            except ProcessLookupError:
                pass
        if self._thread and self._thread is not threading.current_thread():
            self._thread.join()
