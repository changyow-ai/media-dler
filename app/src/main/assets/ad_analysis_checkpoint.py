"""Task-local, complete-candidate analysis journal; never a final cutting plan.

One job owner must serialize restore/commit calls (as for SegmentCache). Each
committed record contains all raw features, the weighted result and the row
before selection. Old bindings and damaged evidence are retained until the job's
existing scratch lifecycle removes them. No decoding, OCR or scoring occurs here.
"""
import copy
import json
import math
import os
from pathlib import Path
import stat
import uuid

import hls_ad_filter as ads

MAX_RECORD_BYTES = 16 * 1024 * 1024
MAX_TOTAL_BYTES = 256 * 1024 * 1024
MAX_CANDIDATES = 4096
MAX_FILES = 20000


class CheckpointError(ads.FilterError):
    """Explicit storage/path/validation failure; caller must preserve source."""


def _json(value, depth=0):
    if depth > 64:
        raise CheckpointError('checkpoint_json_depth_limit')
    if value is None or type(value) in (str, bool, int):
        return
    if type(value) is float and math.isfinite(value):
        return
    if type(value) in (list, tuple):
        for item in value:
            _json(item, depth + 1)
        return
    if type(value) is dict and all(type(key) is str for key in value):
        for item in value.values():
            _json(item, depth + 1)
        return
    raise CheckpointError('checkpoint_nonfinite_or_invalid_json')


def _sha(value):
    _json(value)
    return ads.digest(ads.canonical(value))


def _number(value):
    return type(value) in (int, float) and math.isfinite(value) and value >= 0


def _indices(value):
    return (type(value) is list and bool(value)
            and all(type(item) is int and item >= 0 for item in value)
            and value == sorted(set(value)))


class CandidateJournal:
    """Initialize with ordered reusable candidate descriptors and policy-file SHA.

    source requires identity, manifest, url and optional headers, as read_input.
    runtime_policy_sha256 must bind analysis/OCR code and settings, including this
    module. restore() returns records, completed, saved_elapsed_seconds and an
    explicit invalidation_reason (None normally). commit() accepts the next
    zero-based ordinal and current-attempt seconds since the prior commit
    boundary (first boundary is analysis start), excluding saved work.
    For a larger population, pass its full count and canonical descriptor-array
    SHA while supplying only the bounded reusable prefix. Defaults bind the full
    supplied list, preserving standalone callers. Only a successful atomic commit
    makes a candidate reusable.
    """
    def __init__(self, root, source, runtime_policy_sha256, candidates,
                 score_threshold=55, max_record_bytes=MAX_RECORD_BYTES,
                 max_total_bytes=MAX_TOTAL_BYTES, population_count=None,
                 population_sha256=None):
        self.root = Path(root)
        if not self.root.is_absolute() or '..' in self.root.parts:
            raise CheckpointError('checkpoint_requires_real_absolute_root')
        self._safe(self.root, directory=True)
        if not self.root.is_dir():
            raise CheckpointError('checkpoint_root_missing')
        for value in (max_record_bytes, max_total_bytes):
            if type(value) is not int or value <= 0:
                raise CheckpointError('checkpoint_invalid_byte_limit')
        if (not isinstance(source, dict) or not ads.HEX.fullmatch(str(source.get('identity', '')))
                or type(source.get('manifest')) is not str or not ads.http_url(source.get('url'))
                or type(source.get('headers', {})) is not dict
                or not all(type(k) is str and type(v) is str for k, v in source.get('headers', {}).items())
                or type(runtime_policy_sha256) is not str or not ads.HEX.fullmatch(runtime_policy_sha256)
                or not _number(score_threshold) or score_threshold > 100):
            raise CheckpointError('checkpoint_invalid_binding')
        if type(candidates) is not list or len(candidates) > MAX_CANDIDATES:
            raise CheckpointError('checkpoint_candidate_count_limit')
        self.candidates = copy.deepcopy(candidates)
        for candidate in self.candidates:
            if (type(candidate) is not dict or not _indices(candidate.get('region_indices'))
                    or not _indices(candidate.get('segment_indices'))
                    or not _number(candidate.get('duration_seconds'))):
                raise CheckpointError('checkpoint_invalid_candidate')
        if population_count is None and population_sha256 is None:
            population_count, population_sha256 = len(candidates), _sha(self.candidates)
        if (type(population_count) is not int or population_count < len(candidates)
                or type(population_sha256) is not str or not ads.HEX.fullmatch(population_sha256)):
            raise CheckpointError('checkpoint_invalid_population_binding')
        self.threshold = score_threshold
        self.binding = {'schema': 1, 'snapshot_identity': source['identity'],
            'manifest_sha256': ads.digest(source['manifest'].encode('utf-8')),
            'request_sha256': _sha({'url': source['url'], 'headers': source.get('headers', {})}),
            'runtime_policy_sha256': runtime_policy_sha256,
            'candidates_sha256': population_sha256, 'candidate_count': population_count,
            'reusable_prefix_sha256': _sha(self.candidates), 'reusable_prefix_count': len(candidates),
            'score_threshold': score_threshold}
        self.identity = _sha(self.binding)
        self.storage = self.root / 'analysis-checkpoint'
        self.directory = self.storage / self.identity
        self.max_record_bytes, self.max_total_bytes = max_record_bytes, max_total_bytes
        self.records = None
        self.elapsed = 0.0
        self.previous = self.identity

    @staticmethod
    def _safe(path, directory=False):
        for part in (path, *path.parents):
            if part.is_symlink():
                raise CheckpointError('checkpoint_unsafe_symlink: ' + str(part))
        if path.exists():
            mode = path.stat().st_mode
            if not (stat.S_ISDIR(mode) if directory else stat.S_ISREG(mode)):
                raise CheckpointError('checkpoint_unsafe_file_type: ' + str(path))

    def _usage(self):
        self._safe(self.storage, directory=True)
        total, files = 0, 0
        if not self.storage.exists():
            return 0
        for directory, dirs, names in os.walk(self.storage, followlinks=False):
            for name in dirs:
                files += 1
                if files > MAX_FILES:
                    raise CheckpointError('checkpoint_total_storage_limit')
                self._safe(Path(directory) / name, directory=True)
            for name in names:
                path = Path(directory) / name
                self._safe(path)
                files += 1
                total += path.stat().st_size
                if files > MAX_FILES or total > self.max_total_bytes:
                    raise CheckpointError('checkpoint_total_storage_limit')
        return total

    def _read(self, path):
        self._safe(path)
        if path.stat().st_size > self.max_record_bytes:
            raise CheckpointError('checkpoint_record_byte_limit')
        try:
            with path.open('rb') as stream:
                raw = stream.read(self.max_record_bytes + 1)
            if len(raw) > self.max_record_bytes:
                raise CheckpointError('checkpoint_record_byte_limit')
            value = json.loads(raw)
            _json(value)
            return value
        except (ValueError, UnicodeError, RecursionError):
            raise CheckpointError('checkpoint_invalid_json') from None

    def _write(self, path, value, replace=False):
        self._safe(path)
        _json(value)
        size = len(ads.canonical(value))
        if size > self.max_record_bytes:
            raise CheckpointError('checkpoint_record_byte_limit')
        # Includes retained rejected records, old bindings and interrupted temps.
        # Reserve a full extra record for atomic_json's temporary file.
        if self._usage() + size > self.max_total_bytes:
            raise CheckpointError('checkpoint_total_storage_limit')
        ads.atomic_json(path, value, replace=replace)

    def _archive(self, path):
        self._safe(path, directory=path.is_dir())
        os.rename(path, path.with_name(path.name + '.rejected-' + uuid.uuid4().hex))

    def _validate_payload(self, payload, ordinal):
        if type(payload) is not dict or set(payload) != {
                'features', 'weighted', 'row', 'elapsed_seconds', 'cumulative_elapsed_seconds'}:
            raise CheckpointError('checkpoint_invalid_payload')
        features, weighted, row = (payload[key] for key in ('features', 'weighted', 'row'))
        if any(type(value) is not dict for value in (features, weighted, row)):
            raise CheckpointError('checkpoint_missing_raw_evidence')
        candidate = self.candidates[ordinal]
        if (not _indices(row.get('region_indices')) or not _indices(row.get('segment_indices'))
                or not _number(row.get('duration_seconds'))):
            raise CheckpointError('checkpoint_invalid_row_types')
        if any(row.get(key) != candidate[key] for key in ('region_indices', 'segment_indices', 'duration_seconds')):
            raise CheckpointError('checkpoint_candidate_row_mismatch')
        if 'selection' in row or 'overlapping_passing_ranges' in row:
            raise CheckpointError('checkpoint_requires_preselection_row')
        inputs = features.get('inputs')
        gate = weighted.get('candidate_source_gate')
        if (type(inputs) is not dict or type(inputs.get('candidate')) is not dict
                or features.get('candidate_gate') not in ('valid', 'unknown')
                or type(features.get('ocr')) is not dict
                or type(features.get('errors')) is not list
                or not all(type(e) is str for e in features['errors'])
                or type(features.get('expected_absences', [])) is not list
                or type(gate) is not dict or type(gate.get('valid')) is not bool):
            raise CheckpointError('checkpoint_invalid_raw_gate')
        valid = inputs['candidate'].get('status') == 'ok' and features['candidate_gate'] == 'valid'
        score = weighted.get('score')
        if not _number(score) or score > 100 or gate['valid'] != valid:
            raise CheckpointError('checkpoint_score_or_gate_mismatch')
        decision = 'experimental_ad_candidate' if valid and score >= self.threshold else 'unknown'
        if (weighted.get('decision') != decision or row.get('decision') != decision
                or type(row.get('score')) not in (int, float) or row['score'] != score
                or row.get('status') != ('scored' if valid else 'unknown')
                or row.get('errors') != features['errors']
                or type(row.get('expected_absence_count')) is not int
                or row['expected_absence_count'] != len(features.get('expected_absences', []))):
            raise CheckpointError('checkpoint_row_score_gate_decision_mismatch')
        elapsed, cumulative = payload['elapsed_seconds'], payload['cumulative_elapsed_seconds']
        if (not _number(elapsed) or not _number(cumulative)
                or not math.isclose(cumulative, self.elapsed + elapsed, rel_tol=1e-12, abs_tol=1e-9)):
            raise CheckpointError('checkpoint_elapsed_mismatch')

    def _entry(self, ordinal):
        return self.directory / ('%06d.json' % ordinal)

    def restore(self):
        """Validate a contiguous prefix; retain every rejected/old file untouched."""
        self._usage()
        self.storage.mkdir(exist_ok=True)
        active = self.storage / 'active.json'
        reason = None
        if active.exists():
            try:
                previous = self._read(active)
                if previous != self.binding:
                    if not isinstance(previous, dict):
                        reason = 'checkpoint_active_binding_invalid'
                    elif any(previous.get(k) != self.binding[k] for k in ('snapshot_identity', 'manifest_sha256', 'request_sha256')):
                        reason = 'checkpoint_source_changed'
                    elif previous.get('runtime_policy_sha256') != self.binding['runtime_policy_sha256'] or previous.get('score_threshold') != self.threshold:
                        reason = 'checkpoint_runtime_policy_changed'
                    else:
                        reason = 'checkpoint_candidates_changed'
            except CheckpointError as exc:
                if 'unsafe_' in str(exc):
                    raise
                reason = 'checkpoint_active_binding_invalid'
        self._safe(self.directory, directory=True)
        if reason and self.directory.exists():
            # A changed binding never resurrects an older run of that binding.
            self._archive(self.directory)
        header = self.directory / 'header.json'
        if self.directory.exists():
            try:
                if _sha(self._read(header)) != self.identity:
                    raise CheckpointError('checkpoint_header_mismatch')
            except (CheckpointError, FileNotFoundError) as exc:
                if 'unsafe_' in str(exc):
                    raise
                self._archive(self.directory)
                reason = 'checkpoint_header_invalid'
        self.directory.mkdir(exist_ok=True)
        if not header.exists():
            self._write(header, self.binding)
        self._write(active, self.binding, replace=True)
        self.records, self.elapsed, self.previous = [], 0.0, self.identity
        for ordinal, candidate in enumerate(self.candidates):
            path = self._entry(ordinal)
            if not path.exists():
                if any(self._entry(index).exists() for index in range(ordinal + 1, len(self.candidates))):
                    reason = 'checkpoint_missing_candidate_%d' % ordinal
                break
            try:
                record = self._read(path)
                if (type(record) is not dict or set(record) != {'schema', 'ordinal', 'candidate_sha256', 'previous_sha256', 'payload', 'payload_sha256', 'record_sha256'}
                        or type(record.get('schema')) is not int or record['schema'] != 1
                        or type(record.get('ordinal')) is not int or record['ordinal'] != ordinal
                        or record['candidate_sha256'] != _sha(candidate)
                        or record['previous_sha256'] != self.previous
                        or record['payload_sha256'] != _sha(record['payload'])
                        or record['record_sha256'] != _sha({k: v for k, v in record.items() if k != 'record_sha256'})):
                    raise CheckpointError('checkpoint_record_identity_or_checksum_mismatch')
                self._validate_payload(record['payload'], ordinal)
            except CheckpointError as exc:
                if 'unsafe_' in str(exc):
                    raise
                reason = 'checkpoint_invalid_candidate_%d: %s' % (ordinal, exc)
                break
            self.records.append(record)
            self.elapsed = record['payload']['cumulative_elapsed_seconds']
            self.previous = record['record_sha256']
        return {'records': copy.deepcopy(self.records), 'completed': len(self.records),
                'saved_elapsed_seconds': self.elapsed, 'invalidation_reason': reason}

    def commit(self, ordinal, features, weighted, row, elapsed_seconds):
        """Atomically append only a fully completed candidate; no omitted audit."""
        if self.records is None:
            raise CheckpointError('checkpoint_restore_required')
        if type(ordinal) is not int or ordinal != len(self.records) or ordinal >= len(self.candidates):
            raise CheckpointError('checkpoint_noncontiguous_commit')
        payload = copy.deepcopy({'features': features, 'weighted': weighted, 'row': row,
            'elapsed_seconds': elapsed_seconds,
            'cumulative_elapsed_seconds': self.elapsed + elapsed_seconds if _number(elapsed_seconds) else None})
        _json(payload)
        # The scorer emits coordinate tuples; JSON arrays preserve their full
        # evidence and give the original and restored records identical types.
        payload = json.loads(ads.canonical(payload))
        self._validate_payload(payload, ordinal)
        record = {'schema': 1, 'ordinal': ordinal, 'candidate_sha256': _sha(self.candidates[ordinal]),
                  'previous_sha256': self.previous, 'payload': payload, 'payload_sha256': _sha(payload)}
        record['record_sha256'] = _sha(record)
        path = self._entry(ordinal)
        # Preserve a corrupt or stale suffix, including its original raw evidence.
        if path.exists():
            self._archive(path)
        self._write(path, record)
        self.records.append(record)
        self.previous, self.elapsed = record['record_sha256'], payload['cumulative_elapsed_seconds']
        return copy.deepcopy(record)
