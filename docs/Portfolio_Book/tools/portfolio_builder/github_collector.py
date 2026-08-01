"""Exhaustive GitHub PR/issue capture with child-aware reconciliation."""

from __future__ import annotations

import gzip
import hashlib
import io
import json
import tarfile
from dataclasses import dataclass, replace
from datetime import UTC, datetime
from pathlib import Path
from typing import Callable, Iterable, Mapping

from .github_client import (
    DEFAULT_ACCEPT,
    PATCH_ACCEPT,
    GitHubClient,
    GitHubClientError,
    GitHubPage,
)
from .models import (
    GitHubEndpointFingerprint,
    GitHubSnapshotWindow,
    SourceRecord,
    StoredArtifactMember,
)
from .redaction import redact_text


PER_PAGE = 100
REPOSITORY = "zbnerd/probabilistic-valuation-engine"
COUNT_GAP_REASON = "parent-reported count exceeds accessible endpoint enumeration"


@dataclass(frozen=True, slots=True)
class GitHubCollection:
    kind: str
    numbers: tuple[int, ...]
    updated_at_by_item: dict[str, str]
    records: tuple[SourceRecord, ...]
    endpoint_fingerprints: tuple[GitHubEndpointFingerprint, ...]
    archive_paths: tuple[Path, ...]


@dataclass(frozen=True, slots=True)
class ReconciliationPass:
    pass_number: int
    changed_items: tuple[str, ...]
    changed_endpoint_keys: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class ReconciliationResult:
    records: tuple[SourceRecord, ...]
    window: GitHubSnapshotWindow
    passes: tuple[ReconciliationPass, ...]
    archive_paths: tuple[Path, ...]


@dataclass(frozen=True, slots=True)
class _Enumeration:
    kind: str
    numbers: tuple[int, ...]
    updated_at_by_item: dict[str, str]
    fingerprint: GitHubEndpointFingerprint


@dataclass(frozen=True, slots=True)
class _SafeRecord:
    record: SourceRecord
    stored_bytes: bytes
    suffix: str


@dataclass(frozen=True, slots=True)
class _Hydration:
    safe_records: tuple[_SafeRecord, ...]
    fingerprints: tuple[GitHubEndpointFingerprint, ...]


def _sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _canonical_json(value: object) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        + "\n"
    ).encode("utf-8")


def _credential_key_kind(key: str | None) -> str | None:
    if key is None:
        return None
    normalized = key.lower().replace("_", "").replace("-", "")
    if normalized in {"awsaccesskeyid", "accesskeyid"}:
        return "aws-access-key"
    if normalized in {"awssecretaccesskey", "secretaccesskey"}:
        return "aws-secret-access-key"
    if normalized in {"githubtoken", "githubpat", "ghtoken"}:
        return "github-token"
    if normalized in {"password", "passwd", "secret", "apikey", "token"}:
        return "credential-value"
    return None


def _redact_json_value(value: object, key: str | None = None) -> tuple[object, set[str]]:
    if isinstance(value, str):
        redacted = redact_text(value.encode("utf-8"))
        safe = redacted.value.decode("utf-8")
        kinds = set(redacted.kinds)
        key_kind = _credential_key_kind(key)
        if key_kind is not None and safe != f"[REDACTED:{key_kind}]":
            marker = f"[REDACTED:{key_kind}]"
            safe = marker
            kinds.add(key_kind)
        return safe, kinds
    if isinstance(value, Mapping):
        safe_mapping: dict[object, object] = {}
        kinds: set[str] = set()
        for child_key, child_value in value.items():
            safe_child, child_kinds = _redact_json_value(
                child_value,
                child_key if isinstance(child_key, str) else None,
            )
            safe_mapping[child_key] = safe_child
            kinds.update(child_kinds)
        return safe_mapping, kinds
    if isinstance(value, list):
        safe_items: list[object] = []
        kinds = set()
        for child in value:
            safe_child, child_kinds = _redact_json_value(child)
            safe_items.append(safe_child)
            kinds.update(child_kinds)
        return safe_items, kinds
    return value, set()


def _params_hash(params: Mapping[str, object]) -> str:
    return _sha256(_canonical_json(dict(sorted(params.items()))))


def _utc_now() -> str:
    return datetime.now(UTC).isoformat().replace("+00:00", "Z")


def _base(repository: str) -> str:
    if repository.count("/") != 1 or repository.startswith("/") or repository.endswith("/"):
        raise ValueError("repository must be owner/name")
    return f"/repos/{repository}"


def _require_pages(
    pages: Iterable[GitHubPage],
    endpoint: str,
    *,
    allow_unavailable: bool,
    expected_start: int = 1,
) -> tuple[GitHubPage, ...]:
    values = tuple(pages)
    if not values:
        raise GitHubClientError(f"missing pagination proof: endpoint={endpoint}")
    unavailable = tuple(
        page for page in values if page.availability_status == "confirmed-unavailable"
    )
    if unavailable:
        if allow_unavailable and len(values) == 1:
            return values
        raise GitHubClientError(f"mixed availability pagination: endpoint={endpoint}")
    numbers = tuple(page.page_number for page in values)
    if numbers != tuple(range(expected_start, expected_start + len(values))):
        raise GitHubClientError(f"incomplete pagination: endpoint={endpoint}")
    if any(page.availability_status != "available" for page in values):
        raise GitHubClientError(f"mixed availability pagination: endpoint={endpoint}")
    return values


def _is_unavailable(pages: tuple[GitHubPage, ...]) -> bool:
    return len(pages) == 1 and pages[0].availability_status == "confirmed-unavailable"


def _page_item_count(page: GitHubPage, endpoint: str, endpoint_kind: str) -> int:
    if endpoint_kind == "requested-reviewers":
        return len(_requested_rows(page.json, endpoint))
    if not isinstance(page.json, list):
        raise GitHubClientError(f"malformed paginated payload: endpoint={endpoint}")
    return len(page.json)


def _complete_pages(
    client: GitHubClient,
    endpoint: str,
    params: Mapping[str, object],
    *,
    accept: str = DEFAULT_ACCEPT,
    allow_unavailable: bool = False,
    endpoint_kind: str,
) -> tuple[GitHubPage, ...]:
    pages = _require_pages(
        client.get_pages(endpoint, params),
        endpoint,
        allow_unavailable=allow_unavailable,
    )
    if _is_unavailable(pages):
        return pages
    while _page_item_count(pages[-1], endpoint, endpoint_kind) == PER_PAGE:
        next_page = pages[-1].page_number + 1
        sentinel_params = {**params, "page": next_page}
        sentinel = _require_pages(
            client.get_pages(endpoint, sentinel_params, accept),
            endpoint,
            allow_unavailable=False,
            expected_start=next_page,
        )
        pages = (*pages, *sentinel)
    return pages


def _items(pages: tuple[GitHubPage, ...], endpoint: str) -> tuple[dict[str, object], ...]:
    rows: list[dict[str, object]] = []
    for page in pages:
        if not isinstance(page.json, list):
            raise GitHubClientError(f"malformed paginated payload: endpoint={endpoint}")
        for value in page.json:
            if not isinstance(value, dict):
                raise GitHubClientError(f"malformed child payload: endpoint={endpoint}")
            rows.append(value)
    return tuple(rows)


def _object(pages: tuple[GitHubPage, ...], endpoint: str) -> dict[str, object] | None:
    if _is_unavailable(pages):
        return None
    if len(pages) != 1 or not isinstance(pages[0].json, dict):
        raise GitHubClientError(f"malformed detail payload: endpoint={endpoint}")
    return pages[0].json


def _fingerprint(
    *,
    item_key: str,
    endpoint: str,
    params: Mapping[str, object],
    accept: str,
    pages: tuple[GitHubPage, ...],
    child_ids: Iterable[str] = (),
) -> GitHubEndpointFingerprint:
    page_numbers = tuple(page.page_number for page in pages)
    hashes = tuple(page.response_hash for page in pages)
    unavailable = _is_unavailable(pages)
    availability = "confirmed-unavailable" if unavailable else "available"
    identities = set(child_ids)
    if unavailable:
        identities.add(f"status-code:{pages[0].status_code}")
    return GitHubEndpointFingerprint(
        item_key=item_key,
        endpoint_key=endpoint,
        request_params_sha256=_params_hash(params),
        accept=accept,
        page_numbers=page_numbers,
        page_response_hashes=hashes,
        stable_child_ids=tuple(sorted(identities)),
        availability_status=availability,
    )


def _enumerate(client: GitHubClient, repository: str, kind: str) -> _Enumeration:
    endpoint = f"{_base(repository)}/{'pulls' if kind == 'pull' else 'issues'}"
    params = {"state": "all", "per_page": PER_PAGE}
    pages = _complete_pages(
        client,
        endpoint,
        params,
        endpoint_kind="enumeration",
    )
    rows = _items(pages, endpoint)
    if kind == "issue":
        rows = tuple(row for row in rows if "pull_request" not in row)
    by_number: dict[int, str] = {}
    for row in rows:
        number = row.get("number")
        updated_at = row.get("updated_at")
        if not isinstance(number, int) or isinstance(number, bool) or not isinstance(updated_at, str):
            raise GitHubClientError(f"malformed enumeration row: endpoint={endpoint}")
        if number in by_number:
            raise GitHubClientError(f"duplicate enumeration number: endpoint={endpoint}")
        by_number[number] = updated_at
    item_key = f"{kind}:enumeration"
    return _Enumeration(
        kind=kind,
        numbers=tuple(sorted(by_number)),
        updated_at_by_item={f"{kind}:{number}": by_number[number] for number in sorted(by_number)},
        fingerprint=_fingerprint(
            item_key=item_key,
            endpoint=endpoint,
            params=params,
            accept=DEFAULT_ACCEPT,
            pages=pages,
            child_ids=(f"{kind}:{number}" for number in by_number),
        ),
    )


def _stable_id(endpoint_kind: str, value: Mapping[str, object]) -> str:
    if endpoint_kind == "commits" and isinstance(value.get("sha"), str):
        identity = value["sha"]
    elif endpoint_kind == "files":
        identity = f"{value.get('sha', '')}:{value.get('filename', '')}"
    elif endpoint_kind == "requested-reviewers":
        identity = f"{value.get('reviewer_type', '')}:{value.get('id', value.get('login', value.get('slug', '')))}"
    elif isinstance(value.get("id"), int | str):
        identity = str(value["id"])
    elif isinstance(value.get("node_id"), str):
        identity = value["node_id"]
    else:
        identity = _sha256(_canonical_json(value))
    return f"{endpoint_kind}:{identity}"


def _source_type(kind: str) -> str:
    return {
        "commits": "github-pr-commit",
        "files": "github-pr-file",
        "reviews": "github-review",
        "review-comments": "github-review-comment",
        "conversation-comments": "github-conversation-comment",
        "timeline": "github-timeline-event",
        "reactions": "github-reaction",
        "requested-reviewers": "github-requested-reviewer",
    }[kind]


def _record(
    *,
    source_id: str,
    source_type: str,
    locator: str,
    snapshot_id: str,
    title: str,
    raw: bytes,
    safe: bytes,
    observed_raw_hash: str | None = None,
    fetched_at: str,
    captured_updated_at: str | None,
    availability: str = "available",
    privacy: tuple[str, ...] = (),
    payload: Mapping[str, object] | None = None,
) -> SourceRecord:
    raw_hash = observed_raw_hash or _sha256(raw)
    values = dict(payload or {})
    values.update(
        {
            "fetched_at": fetched_at,
            "captured_updated_at": captured_updated_at,
            "response_raw_sha256": raw_hash,
        }
    )
    return SourceRecord(
        source_id=source_id,
        source_type=source_type,
        source_locator=locator,
        snapshot_id=snapshot_id,
        title=title,
        evidence_scope="project-evidence",
        claim_authority="primary-record",
        recorded_status="captured" if availability == "available" else "unavailable",
        recorded_at=captured_updated_at,
        raw_hash=raw_hash,
        stored_hash=_sha256(safe),
        raw_archive_locator=None,
        stored_members=(),
        explicit_relations=(),
        case_ids=(),
        classification="unreviewed",
        record_only_reason=None if availability == "available" else "confirmed-unavailable",
        availability_status=availability,
        privacy_redactions=privacy,
        parse_status="parsed",
        payload=values,
    )


def _safe_record(
    *,
    source_id: str,
    source_type: str,
    locator: str,
    snapshot_id: str,
    title: str,
    value: Mapping[str, object],
    fetched_at: str,
    captured_updated_at: str | None,
    response_hash: str,
) -> _SafeRecord:
    raw = _canonical_json(value)
    safe_value, value_redactions = _redact_json_value(value)
    safe = _canonical_json(safe_value)
    redacted_title = redact_text(title.encode("utf-8"))
    record = _record(
        source_id=source_id,
        source_type=source_type,
        locator=locator,
        snapshot_id=snapshot_id,
        title=redacted_title.value.decode("utf-8", errors="replace"),
        raw=raw,
        safe=safe,
        observed_raw_hash=response_hash,
        fetched_at=fetched_at,
        captured_updated_at=captured_updated_at,
        privacy=tuple(sorted(value_redactions | set(redacted_title.kinds))),
        payload={"endpoint_response_raw_sha256": response_hash, "value": safe_value},
    )
    return _SafeRecord(record, safe, "json")


def _availability_record(
    *,
    item_key: str,
    endpoint: str,
    snapshot_id: str,
    page: GitHubPage,
    params: Mapping[str, object],
    accept: str,
) -> _SafeRecord:
    safe_payload = {
        "accept": accept,
        "availability_status": "confirmed-unavailable",
        "confirmed_at": page.fetched_at,
        "endpoint": endpoint,
        "observed_body_sha256": page.response_hash,
        "request_params": dict(sorted(params.items())),
        "status_code": page.status_code,
    }
    safe = _canonical_json(safe_payload)
    source_id = "GH-AVAIL-" + _sha256(f"{item_key}:{endpoint}".encode())[:24]
    return _SafeRecord(
        _record(
            source_id=source_id,
            source_type="github-availability",
            locator=f"github:{endpoint}",
            snapshot_id=snapshot_id,
            title=f"Unavailable {endpoint}",
            raw=safe,
            safe=safe,
            observed_raw_hash=page.response_hash,
            fetched_at=page.fetched_at,
            captured_updated_at=None,
            availability="confirmed-unavailable",
            payload=safe_payload,
        ),
        safe,
        "json",
    )


def _count_gap_record(
    *,
    item_key: str,
    endpoint: str,
    endpoint_kind: str,
    snapshot_id: str,
    pages: tuple[GitHubPage, ...],
    params: Mapping[str, object],
    accept: str,
    child_ids: tuple[str, ...],
    expected_count: int,
    parent_detail_page: GitHubPage,
    parent_updated_at: str,
) -> tuple[_SafeRecord, str]:
    observed_count = len(child_ids)
    if expected_count <= observed_count:
        raise ValueError("count gap requires expected_count > observed_count")
    stable_evidence = {
        "contract": "github-count-gap-v1",
        "item_key": item_key,
        "endpoint": endpoint,
        "endpoint_kind": endpoint_kind,
        "expected_count": expected_count,
        "observed_count": observed_count,
        "missing_count": expected_count - observed_count,
        "parent_detail_response_sha256": parent_detail_page.response_hash,
        "parent_updated_at": parent_updated_at,
        "child_accept": accept,
        "child_request_params": dict(sorted(params.items())),
        "child_request_params_sha256": _params_hash(params),
        "child_page_numbers": [page.page_number for page in pages],
        "child_page_response_sha256": [page.response_hash for page in pages],
        "child_stable_ids": list(sorted(child_ids)),
    }
    token = "count-gap:" + _sha256(_canonical_json(stable_evidence))
    evidence_chain = {
        **stable_evidence,
        "parent_detail_fetched_at": parent_detail_page.fetched_at,
        "child_page_fetched_at": [page.fetched_at for page in pages],
    }
    raw = _canonical_json(evidence_chain)
    raw_hash = _sha256(raw)
    safe_payload = {
        **evidence_chain,
        "evidence_chain_sha256": raw_hash,
        "gap_token": token,
        "reason": COUNT_GAP_REASON,
    }
    safe = _canonical_json(safe_payload)
    source_id = "GH-COUNT-GAP-" + _sha256(f"{item_key}:{endpoint}".encode())[:24]
    record = _record(
        source_id=source_id,
        source_type="github-count-gap",
        locator=f"github:{endpoint}#{token}",
        snapshot_id=snapshot_id,
        title=f"Count gap {item_key} {endpoint_kind}",
        raw=raw,
        safe=safe,
        fetched_at=pages[-1].fetched_at,
        captured_updated_at=parent_updated_at,
        availability="confirmed-unavailable",
        payload=safe_payload,
    )
    return (
        _SafeRecord(
            replace(
                record,
                classification="record-only",
                record_only_reason=COUNT_GAP_REASON,
            ),
            safe,
            "json",
        ),
        token,
    )


def _requested_rows(value: object, endpoint: str) -> tuple[dict[str, object], ...]:
    if not isinstance(value, dict):
        raise GitHubClientError(f"malformed requested reviewers: endpoint={endpoint}")
    rows: list[dict[str, object]] = []
    for key, reviewer_type in (("users", "user"), ("teams", "team")):
        values = value.get(key, [])
        if not isinstance(values, list) or not all(isinstance(item, dict) for item in values):
            raise GitHubClientError(f"malformed requested reviewers: endpoint={endpoint}")
        rows.extend({**item, "reviewer_type": reviewer_type} for item in values)
    return tuple(rows)


def _rows_with_pages(
    pages: tuple[GitHubPage, ...], endpoint: str, endpoint_kind: str
) -> tuple[tuple[dict[str, object], GitHubPage], ...]:
    rows: list[tuple[dict[str, object], GitHubPage]] = []
    for page in pages:
        values = (
            _requested_rows(page.json, endpoint)
            if endpoint_kind == "requested-reviewers"
            else _items((page,), endpoint)
        )
        rows.extend((value, page) for value in values)
    return tuple(rows)


def _capture_endpoint(
    *,
    client: GitHubClient,
    item_key: str,
    endpoint: str,
    endpoint_kind: str,
    snapshot_id: str,
    fetched_at: str,
    captured_updated_at: str | None,
    independent_count: int | None,
    parent_detail_page: GitHubPage,
) -> tuple[tuple[_SafeRecord, ...], GitHubEndpointFingerprint]:
    params = {"per_page": PER_PAGE}
    pages = _complete_pages(
        client,
        endpoint,
        params,
        allow_unavailable=True,
        endpoint_kind=endpoint_kind,
    )
    if _is_unavailable(pages):
        return (
            (
                _availability_record(
                    item_key=item_key,
                    endpoint=endpoint,
                    snapshot_id=snapshot_id,
                    page=pages[0],
                    params=params,
                    accept=DEFAULT_ACCEPT,
                ),
            ),
            _fingerprint(
                item_key=item_key,
                endpoint=endpoint,
                params=params,
                accept=DEFAULT_ACCEPT,
                pages=pages,
            ),
        )
    rows_with_pages = _rows_with_pages(pages, endpoint, endpoint_kind)
    rows = tuple(row for row, _ in rows_with_pages)
    ids = tuple(_stable_id(endpoint_kind, row) for row in rows)
    if len(ids) != len(set(ids)):
        raise GitHubClientError(f"duplicate child IDs: endpoint={endpoint}")
    safe_records = tuple(
        _safe_record(
            source_id="GH-" + _sha256(f"{item_key}:{endpoint}:{stable_id}".encode())[:32],
            source_type=_source_type(endpoint_kind),
            locator=f"github:{endpoint}#{stable_id}",
            snapshot_id=snapshot_id,
            title=f"{item_key} {stable_id}",
            value=row,
            fetched_at=fetched_at,
            captured_updated_at=captured_updated_at,
            response_hash=page.response_hash,
        )
        for (row, page), stable_id in zip(rows_with_pages, ids, strict=True)
    )
    fingerprint_ids = ids
    if independent_count is not None:
        observed_count = len(ids)
        if independent_count < observed_count:
            raise GitHubClientError(
                f"count mismatch: endpoint={endpoint_kind} expected={independent_count} actual={observed_count}"
            )
        if independent_count > observed_count:
            if captured_updated_at is None:
                raise GitHubClientError(
                    f"count gap missing parent updated_at: endpoint={endpoint_kind}"
                )
            gap, gap_token = _count_gap_record(
                item_key=item_key,
                endpoint=endpoint,
                endpoint_kind=endpoint_kind,
                snapshot_id=snapshot_id,
                pages=pages,
                params=params,
                accept=DEFAULT_ACCEPT,
                child_ids=ids,
                expected_count=independent_count,
                parent_detail_page=parent_detail_page,
                parent_updated_at=captured_updated_at,
            )
            safe_records = (*safe_records, gap)
            fingerprint_ids = (*ids, gap_token)
    return safe_records, _fingerprint(
        item_key=item_key,
        endpoint=endpoint,
        params=params,
        accept=DEFAULT_ACCEPT,
        pages=pages,
        child_ids=fingerprint_ids,
    )


def _detail_count(detail: Mapping[str, object], field: str) -> int:
    value = detail.get(field)
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        raise GitHubClientError(f"invalid detail count: {field}")
    return value


def _expected_counts(detail: Mapping[str, object], kind: str) -> dict[str, int]:
    expected = {"conversation-comments": _detail_count(detail, "comments")}
    if kind == "pull":
        expected.update(
            {
                "commits": _detail_count(detail, "commits"),
                "files": _detail_count(detail, "changed_files"),
                "review-comments": _detail_count(detail, "review_comments"),
            }
        )
    return expected


def _hydrate_item(
    client: GitHubClient,
    repository: str,
    snapshot_id: str,
    kind: str,
    number: int,
    now: Callable[[], str],
) -> _Hydration:
    base = _base(repository)
    item_key = f"{kind}:{number}"
    fetched_at = now()
    detail_endpoint = f"{base}/{'pulls' if kind == 'pull' else 'issues'}/{number}"
    detail_pages = _require_pages(
        client.get_pages(detail_endpoint),
        detail_endpoint,
        allow_unavailable=True,
    )
    detail_fingerprint = _fingerprint(
        item_key=item_key, endpoint=detail_endpoint, params={}, accept=DEFAULT_ACCEPT, pages=detail_pages
    )
    if _is_unavailable(detail_pages):
        return _Hydration(
            (
                _availability_record(
                    item_key=item_key,
                    endpoint=detail_endpoint,
                    snapshot_id=snapshot_id,
                    page=detail_pages[0],
                    params={},
                    accept=DEFAULT_ACCEPT,
                ),
            ),
            (detail_fingerprint,),
        )
    detail = _object(detail_pages, detail_endpoint)
    assert detail is not None
    captured_updated_at = detail.get("updated_at")
    if not isinstance(captured_updated_at, str):
        raise GitHubClientError(f"detail missing updated_at: endpoint={detail_endpoint}")
    expected_counts = _expected_counts(detail, kind)
    parent_type = "github-pull-request" if kind == "pull" else "github-issue"
    safe: list[_SafeRecord] = [
        _safe_record(
            source_id=f"GH-{'PR' if kind == 'pull' else 'ISSUE'}-{number}",
            source_type=parent_type,
            locator=f"github:{repository}/{'pull' if kind == 'pull' else 'issues'}/{number}",
            snapshot_id=snapshot_id,
            title=str(detail.get("title", f"{kind} {number}")),
            value=detail,
            fetched_at=fetched_at,
            captured_updated_at=captured_updated_at,
            response_hash=detail_pages[0].response_hash,
        )
    ]
    fingerprints = [detail_fingerprint]
    endpoints = [
        (f"{base}/issues/{number}/comments", "conversation-comments"),
        (f"{base}/issues/{number}/timeline", "timeline"),
        (f"{base}/issues/{number}/reactions", "reactions"),
    ]
    if kind == "pull":
        endpoints = [
            (f"{base}/pulls/{number}/commits", "commits"),
            (f"{base}/pulls/{number}/files", "files"),
            (f"{base}/pulls/{number}/reviews", "reviews"),
            (f"{base}/pulls/{number}/comments", "review-comments"),
            *endpoints,
            (f"{base}/pulls/{number}/requested_reviewers", "requested-reviewers"),
        ]
    for endpoint, endpoint_kind in endpoints:
        records, fingerprint = _capture_endpoint(
            client=client,
            item_key=item_key,
            endpoint=endpoint,
            endpoint_kind=endpoint_kind,
            snapshot_id=snapshot_id,
            fetched_at=fetched_at,
            captured_updated_at=captured_updated_at,
            independent_count=expected_counts.get(endpoint_kind),
            parent_detail_page=detail_pages[0],
        )
        safe.extend(records)
        fingerprints.append(fingerprint)
    if kind == "pull":
        patch_endpoint = f"{base}/pulls/{number}.patch"
        patch_page = client.get_bytes_page(patch_endpoint, accept=PATCH_ACCEPT)
        patch_fingerprint = _fingerprint(
            item_key=item_key,
            endpoint=patch_endpoint,
            params={},
            accept=PATCH_ACCEPT,
            pages=(patch_page,),
        )
        fingerprints.append(patch_fingerprint)
        if patch_page.availability_status == "confirmed-unavailable":
            safe.append(
                _availability_record(
                    item_key=item_key,
                    endpoint=patch_endpoint,
                    snapshot_id=snapshot_id,
                    page=patch_page,
                    params={},
                    accept=PATCH_ACCEPT,
                )
            )
        else:
            patch = patch_page.body
            redacted = redact_text(patch)
            safe.append(
                _SafeRecord(
                    _record(
                        source_id=f"GH-PR-{number}-PATCH",
                        source_type="github-patch",
                        locator=f"github:{repository}/pull/{number}.patch",
                        snapshot_id=snapshot_id,
                        title=f"PR {number} patch",
                        raw=patch,
                        safe=redacted.value,
                        fetched_at=fetched_at,
                        captured_updated_at=captured_updated_at,
                        privacy=redacted.kinds,
                        payload={"patch_raw_sha256": redacted.raw_hash, "patch_stored_sha256": redacted.stored_hash},
                    ),
                    redacted.value,
                    "patch",
                )
            )
    return _Hydration(tuple(safe), tuple(fingerprints))


def _tar_info(name: str, size: int) -> tarfile.TarInfo:
    info = tarfile.TarInfo(name)
    info.size = size
    info.mtime = 0
    info.mode = 0o644
    info.uid = info.gid = 0
    info.uname = info.gname = ""
    return info


def _write_archive(
    archive_dir: Path, name: str, safe_records: tuple[_SafeRecord, ...]
) -> tuple[tuple[SourceRecord, ...], Path]:
    archive_dir.mkdir(parents=True, exist_ok=True)
    filename = f"github-records-{name}.tar.gz"
    path = archive_dir / filename
    ordered = tuple(sorted(safe_records, key=lambda value: value.record.source_id))
    members: dict[str, StoredArtifactMember] = {}
    tar_buffer = io.BytesIO()
    with tarfile.open(fileobj=tar_buffer, mode="w", format=tarfile.USTAR_FORMAT) as archive:
        for item in ordered:
            member_name = f"records/{item.record.source_id}.{item.suffix}"
            archive.addfile(_tar_info(member_name, len(item.stored_bytes)), io.BytesIO(item.stored_bytes))
            members[item.record.source_id] = StoredArtifactMember(
                member_id=item.record.source_id,
                locator=f"{filename}#{member_name}",
                ordinal=1,
                total=1,
                byte_count=len(item.stored_bytes),
                sha256=_sha256(item.stored_bytes),
            )
    compressed = io.BytesIO()
    with gzip.GzipFile(fileobj=compressed, mode="wb", compresslevel=9, mtime=0) as stream:
        stream.write(tar_buffer.getvalue())
    path.write_bytes(compressed.getvalue())
    records = tuple(replace(item.record, stored_members=(members[item.record.source_id],)) for item in ordered)
    return records, path


def _collect_enumeration(
    client: GitHubClient,
    repository: str,
    snapshot_id: str,
    archive_dir: str | Path,
    enumeration: _Enumeration,
    *,
    now: Callable[[], str],
) -> GitHubCollection:
    hydrations = tuple(
        _hydrate_item(client, repository, snapshot_id, enumeration.kind, number, now)
        for number in enumeration.numbers
    )
    safe_records = tuple(record for hydration in hydrations for record in hydration.safe_records)
    fingerprints = (enumeration.fingerprint,) + tuple(
        fingerprint for hydration in hydrations for fingerprint in hydration.fingerprints
    )
    records, archive = _write_archive(Path(archive_dir), f"{enumeration.kind}s", safe_records)
    return GitHubCollection(
        kind=enumeration.kind,
        numbers=enumeration.numbers,
        updated_at_by_item=enumeration.updated_at_by_item,
        records=records,
        endpoint_fingerprints=tuple(sorted(fingerprints, key=lambda value: (value.item_key, value.endpoint_key))),
        archive_paths=(archive,),
    )


def collect_pull_requests(
    client: GitHubClient,
    repository: str,
    snapshot_id: str,
    archive_dir: str | Path,
) -> GitHubCollection:
    enumeration = _enumerate(client, repository, "pull")
    return _collect_enumeration(client, repository, snapshot_id, archive_dir, enumeration, now=_utc_now)


def collect_issues(
    client: GitHubClient,
    repository: str,
    snapshot_id: str,
    archive_dir: str | Path,
) -> GitHubCollection:
    enumeration = _enumerate(client, repository, "issue")
    return _collect_enumeration(client, repository, snapshot_id, archive_dir, enumeration, now=_utc_now)


def _fingerprint_map(collections: tuple[GitHubCollection, ...]) -> dict[tuple[str, str], GitHubEndpointFingerprint]:
    return {
        (fingerprint.item_key, fingerprint.endpoint_key): fingerprint
        for collection in collections
        for fingerprint in collection.endpoint_fingerprints
    }


def _changed(
    previous: tuple[GitHubCollection, ...], current: tuple[GitHubCollection, ...]
) -> tuple[tuple[str, ...], tuple[str, ...]]:
    old_updates = {key: value for collection in previous for key, value in collection.updated_at_by_item.items()}
    new_updates = {key: value for collection in current for key, value in collection.updated_at_by_item.items()}
    old_fingerprints = _fingerprint_map(previous)
    new_fingerprints = _fingerprint_map(current)
    fingerprint_keys = set(old_fingerprints) | set(new_fingerprints)
    changed_keys = tuple(
        sorted(f"{item_key}|{endpoint}" for item_key, endpoint in fingerprint_keys if old_fingerprints.get((item_key, endpoint)) != new_fingerprints.get((item_key, endpoint)))
    )
    items = set(old_updates) ^ set(new_updates)
    items.update(key for key in set(old_updates) & set(new_updates) if old_updates[key] != new_updates[key])
    items.update(value.split("|", 1)[0] for value in changed_keys)
    items.discard("pull:enumeration")
    items.discard("issue:enumeration")
    return tuple(sorted(items)), changed_keys


def reconcile_github(
    *,
    client: GitHubClient,
    repository: str = REPOSITORY,
    snapshot_id: str,
    archive_dir: str | Path,
    now: Callable[[], str] = _utc_now,
    max_passes: int = 12,
) -> ReconciliationResult:
    """Collect until one complete re-enumeration and child probe has zero delta."""
    if max_passes < 1:
        raise ValueError("max_passes must be positive")
    enumeration_started_at = now()

    def enumerate() -> tuple[_Enumeration, _Enumeration]:
        return (
            _enumerate(client, repository, "pull"),
            _enumerate(client, repository, "issue"),
        )

    def hydrate(
        enumerations: tuple[_Enumeration, _Enumeration],
    ) -> tuple[GitHubCollection, GitHubCollection]:
        pulls, issues = enumerations
        return (
            _collect_enumeration(client, repository, snapshot_id, archive_dir, pulls, now=now),
            _collect_enumeration(client, repository, snapshot_id, archive_dir, issues, now=now),
        )

    def capture() -> tuple[GitHubCollection, GitHubCollection]:
        pulls = _enumerate(client, repository, "pull")
        issues = _enumerate(client, repository, "issue")
        return hydrate((pulls, issues))

    initial_enumerations = enumerate()
    enumeration_completed_at = now()
    current = hydrate(initial_enumerations)
    passes = [
        ReconciliationPass(
            pass_number=0,
            changed_items=tuple(
                sorted(key for collection in current for key in collection.updated_at_by_item)
            ),
            changed_endpoint_keys=(),
        )
    ]
    for pass_number in range(1, max_passes + 1):
        candidate = capture()
        changed_items, changed_keys = _changed(current, candidate)
        passes.append(ReconciliationPass(pass_number, changed_items, changed_keys))
        if not changed_items and not changed_keys:
            current = candidate
            break
        # The conditional fingerprint pass hydrated every endpoint of each
        # changed/new item, so retain those exact post-detection responses and
        # require the following full pass to prove zero delta.
        current = candidate
    else:
        raise GitHubClientError("GitHub reconciliation did not reach a zero-delta pass")

    reconciled_at = now()
    pulls, issues = current
    updated = {**pulls.updated_at_by_item, **issues.updated_at_by_item}
    fingerprints = tuple(
        sorted(
            (*pulls.endpoint_fingerprints, *issues.endpoint_fingerprints),
            key=lambda value: (value.item_key, value.endpoint_key),
        )
    )
    return ReconciliationResult(
        records=tuple(sorted((*pulls.records, *issues.records), key=lambda value: value.source_id)),
        window=GitHubSnapshotWindow(
            enumeration_started_at=enumeration_started_at,
            enumeration_completed_at=enumeration_completed_at,
            reconciled_at=reconciled_at,
            pull_request_numbers=pulls.numbers,
            issue_numbers=issues.numbers,
            updated_at_by_item=updated,
            endpoint_fingerprints=fingerprints,
        ),
        passes=tuple(passes),
        archive_paths=(*pulls.archive_paths, *issues.archive_paths),
    )
