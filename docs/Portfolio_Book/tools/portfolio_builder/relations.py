"""Derive only mechanically explicit relationships between captured records.

This module intentionally has no fuzzy matching.  Dates, titles, filenames,
commands, environments, numeric values, and abbreviated object names are not
relationship evidence.
"""

from __future__ import annotations

import hashlib
import json
import posixpath
import re
from dataclasses import dataclass, replace
from typing import Iterable, Mapping

from .models import DocumentClaim, ExplicitRelation, SourceRecord


_FULL_SHA = re.compile(r"(?<![0-9a-f])([0-9a-f]{40})(?![0-9a-f])", re.IGNORECASE)
_PR_REFERENCE = re.compile(
    r"(?:\bPR\s*#|https://github\.com/[^/\s]+/[^/\s]+/pull/)(\d+)\b",
    re.IGNORECASE,
)
_ISSUE_REFERENCE = re.compile(
    r"(?:\bissue\s*#|https://github\.com/[^/\s]+/[^/\s]+/issues/)(\d+)\b",
    re.IGNORECASE,
)
_MARKDOWN_LINK = re.compile(r"\[[^\]]*\]\(([^)\s]+)(?:\s+[^)]*)?\)")
_RUN_KEYS = frozenset({"run_id", "execution_id", "stable_run_id"})
_DIFF_HASH_KEYS = frozenset(
    {"diff_sha256", "patch_sha256", "patch_raw_sha256", "patch_stored_sha256"}
)
_CLOSING_EVENTS = frozenset(
    {"closed", "connected", "cross-referenced", "referenced", "merged"}
)


def _sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _canonical(value: object) -> bytes:
    return json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")


def _walk(value: object, locator: str = "payload") -> Iterable[tuple[str, object]]:
    if isinstance(value, Mapping):
        for key in sorted(value, key=lambda item: str(item).encode("utf-8")):
            yield from _walk(value[key], f"{locator}.{key}")
    elif isinstance(value, list | tuple):
        for index, item in enumerate(value):
            yield from _walk(item, f"{locator}[{index}]")
    else:
        yield locator, value


@dataclass(frozen=True, slots=True)
class RelationCandidate:
    """One relation plus the source record which owns it."""

    owner_source_id: str
    relation: ExplicitRelation

    def to_dict(self) -> dict[str, object]:
        return {
            "owner_source_id": self.owner_source_id,
            "relation": self.relation.to_dict(),
        }


def _candidate(
    owner: str,
    relation_type: str,
    target: str,
    locator: str,
    evidence: object,
) -> RelationCandidate:
    evidence_hash = _sha256(_canonical(evidence))
    return RelationCandidate(
        owner,
        ExplicitRelation.create(
            owner_source_id=owner,
            relation_type=relation_type,
            target_source_id=target,
            evidence_locator=locator,
            evidence_hash=evidence_hash,
        ),
    )


def _target_maps(
    sources: tuple[SourceRecord, ...],
) -> tuple[dict[str, str], dict[int, str], dict[int, str], dict[str, str]]:
    commits: dict[str, str] = {}
    pull_requests: dict[int, str] = {}
    issues: dict[int, str] = {}
    documents: dict[str, str] = {}
    for source in sources:
        if source.source_type == "git-commit":
            sha = source.payload.get("commit_sha")
            if isinstance(sha, str) and re.fullmatch(r"[0-9a-f]{40}", sha, re.IGNORECASE):
                commits[sha.lower()] = source.source_id
        if source.source_type == "github-pull-request":
            match = re.fullmatch(r"GH-PR-(\d+)", source.source_id)
            if match:
                pull_requests[int(match.group(1))] = source.source_id
        if source.source_type == "github-issue":
            match = re.fullmatch(r"GH-ISSUE-(\d+)", source.source_id)
            if match:
                issues[int(match.group(1))] = source.source_id
        if source.source_locator.startswith("git:"):
            documents[source.source_locator.removeprefix("git:")] = source.source_id
    return commits, pull_requests, issues, documents


def _api_commit_relation(
    source: SourceRecord, commits: Mapping[str, str]
) -> Iterable[RelationCandidate]:
    if source.source_type != "github-pr-commit":
        return ()
    value = source.payload.get("value")
    sha = value.get("sha") if isinstance(value, Mapping) else None
    if not isinstance(sha, str) or not re.fullmatch(r"[0-9a-f]{40}", sha, re.IGNORECASE):
        return ()
    target = commits.get(sha.lower())
    if target is None or target == source.source_id:
        return ()
    return (_candidate(source.source_id, "api-commit-sha", target, "payload.value.sha", sha),)


def _github_event_relations(
    source: SourceRecord,
    commits: Mapping[str, str],
    pulls: Mapping[int, str],
    issues: Mapping[int, str],
) -> Iterable[RelationCandidate]:
    if source.source_type != "github-timeline-event":
        return ()
    value = source.payload.get("value")
    if not isinstance(value, Mapping) or value.get("event") not in _CLOSING_EVENTS:
        return ()
    candidates: list[RelationCandidate] = []
    commit = value.get("commit_id")
    if isinstance(commit, str) and re.fullmatch(r"[0-9a-f]{40}", commit, re.IGNORECASE):
        target = commits.get(commit.lower())
        if target is not None and target != source.source_id:
            candidates.append(
                _candidate(
                    source.source_id,
                    "github-closing-event",
                    target,
                    "payload.value.commit_id",
                    {"event": value["event"], "commit_id": commit},
                )
            )
    for key, targets, relation_type in (
        ("pull_request", pulls, "github-cross-reference"),
        ("issue", issues, "github-closing-event"),
    ):
        linked = value.get(key)
        number = linked.get("number") if isinstance(linked, Mapping) else None
        if isinstance(number, int) and not isinstance(number, bool):
            target = targets.get(number)
            if target is not None and target != source.source_id:
                candidates.append(
                    _candidate(
                        source.source_id,
                        relation_type,
                        target,
                        f"payload.value.{key}.number",
                        {"event": value["event"], "number": number},
                    )
                )
    nested_source = value.get("source")
    if isinstance(nested_source, Mapping):
        for key, targets, relation_type in (
            ("issue", issues, "github-cross-reference"),
            ("pull_request", pulls, "github-cross-reference"),
        ):
            linked = nested_source.get(key)
            number = linked.get("number") if isinstance(linked, Mapping) else None
            if isinstance(number, int) and not isinstance(number, bool):
                target = targets.get(number)
                if target is not None and target != source.source_id:
                    candidates.append(
                        _candidate(
                            source.source_id,
                            relation_type,
                            target,
                            f"payload.value.source.{key}.number",
                            {"event": value["event"], "number": number},
                        )
                    )
    return tuple(candidates)


def _text_relations(
    source: SourceRecord,
    commits: Mapping[str, str],
    pulls: Mapping[int, str],
    issues: Mapping[int, str],
    documents: Mapping[str, str],
) -> Iterable[RelationCandidate]:
    candidates: list[RelationCandidate] = []
    for locator, value in _walk(source.payload):
        if not isinstance(value, str):
            continue
        for match in _FULL_SHA.finditer(value):
            sha = match.group(1).lower()
            target = commits.get(sha)
            if target is not None and target != source.source_id:
                candidates.append(
                    _candidate(
                        source.source_id,
                        "explicit-commit-reference",
                        target,
                        f"{locator}#chars={match.start(1)}-{match.end(1)}",
                        match.group(1),
                    )
                )
        for pattern, targets, relation_type in (
            (_PR_REFERENCE, pulls, "explicit-pr-reference"),
            (_ISSUE_REFERENCE, issues, "explicit-issue-reference"),
        ):
            for match in pattern.finditer(value):
                target = targets.get(int(match.group(1)))
                if target is not None and target != source.source_id:
                    candidates.append(
                        _candidate(
                            source.source_id,
                            relation_type,
                            target,
                            f"{locator}#chars={match.start()}-{match.end()}",
                            match.group(0),
                        )
                    )
        for match in _MARKDOWN_LINK.finditer(value):
            destination = match.group(1).split("#", 1)[0]
            if destination.startswith("./"):
                destination = destination[2:]
            target = documents.get(destination)
            if target is None and source.source_locator.startswith("git:"):
                owner_path = source.source_locator.removeprefix("git:")
                resolved = posixpath.normpath(
                    posixpath.join(posixpath.dirname(owner_path), destination)
                )
                target = documents.get(resolved)
            if target is not None and target != source.source_id:
                candidates.append(
                    _candidate(
                        source.source_id,
                        "document-link",
                        target,
                        f"{locator}#chars={match.start(1)}-{match.end(1)}",
                        match.group(1),
                    )
                )
    return tuple(candidates)


def _diff_hash_relations(
    source: SourceRecord, diff_hashes: Mapping[str, str]
) -> Iterable[RelationCandidate]:
    if source.source_type not in {"ai-trace-entry", "ai-trace-file"}:
        return ()
    candidates: list[RelationCandidate] = []
    for locator, value in _walk(source.payload):
        key = locator.rsplit(".", 1)[-1]
        if key not in _DIFF_HASH_KEYS or not isinstance(value, str):
            continue
        if not re.fullmatch(r"[0-9a-f]{64}", value, re.IGNORECASE):
            continue
        target = diff_hashes.get(value.lower())
        if target is not None and target != source.source_id:
            candidates.append(
                _candidate(source.source_id, "exact-diff-hash", target, locator, value)
            )
    return tuple(candidates)


def _run_identifiers(source: SourceRecord) -> tuple[tuple[str, str, str], ...]:
    values: list[tuple[str, str, str]] = []
    for locator, value in _walk(source.payload):
        key = locator.rsplit(".", 1)[-1]
        if key in _RUN_KEYS and isinstance(value, str) and value.strip() == value and value:
            values.append((key, value, locator))
    return tuple(values)


def _same_execution_relations(
    sources: tuple[SourceRecord, ...]
) -> Iterable[RelationCandidate]:
    groups: dict[tuple[str, str], list[tuple[SourceRecord, str]]] = {}
    for source in sources:
        for key, value, locator in _run_identifiers(source):
            groups.setdefault((key, value), []).append((source, locator))
    candidates: list[RelationCandidate] = []
    for (key, value), linked in sorted(groups.items()):
        unique = sorted(
            {source.source_id: (source, locator) for source, locator in linked}.values(),
            key=lambda item: item[0].source_id.encode("utf-8"),
        )
        if len(unique) < 2:
            continue
        anchor, anchor_locator = unique[0]
        for source, locator in unique[1:]:
            evidence = {"field": key, "stable_identifier": value}
            candidates.append(
                _candidate(source.source_id, "same-execution", anchor.source_id, locator, evidence)
            )
            candidates.append(
                _candidate(anchor.source_id, "same-execution", source.source_id, anchor_locator, evidence)
            )
    return tuple(candidates)


def derive_explicit_relations(
    sources: Iterable[SourceRecord],
) -> tuple[RelationCandidate, ...]:
    """Return deterministic relations supported by exact captured fields only."""
    frozen = tuple(sources)
    source_ids = [source.source_id for source in frozen]
    if len(source_ids) != len(set(source_ids)):
        raise ValueError("duplicate source ID while deriving relations")
    commits, pulls, issues, documents = _target_maps(frozen)
    diff_hashes: dict[str, str] = {}
    for source in frozen:
        if source.source_type == "git-diff":
            for value in (source.raw_hash, source.stored_hash):
                existing = diff_hashes.get(value.lower())
                if existing is not None and existing != source.source_id:
                    raise ValueError(f"ambiguous exact diff hash: {value}")
                diff_hashes[value.lower()] = source.source_id

    candidates: list[RelationCandidate] = []
    for source in frozen:
        candidates.extend(_api_commit_relation(source, commits))
        candidates.extend(_github_event_relations(source, commits, pulls, issues))
        candidates.extend(_text_relations(source, commits, pulls, issues, documents))
        candidates.extend(_diff_hash_relations(source, diff_hashes))
    candidates.extend(_same_execution_relations(frozen))
    ordered = tuple(
        sorted(
            candidates,
            key=lambda item: (
                item.relation.relation_id.encode("utf-8"),
                item.owner_source_id.encode("utf-8"),
            ),
        )
    )
    relation_ids = [item.relation.relation_id for item in ordered]
    if len(relation_ids) != len(set(relation_ids)):
        raise ValueError("duplicate relation_id generated from explicit evidence")
    return ordered


def attach_explicit_relations(
    sources: Iterable[SourceRecord],
    candidates: Iterable[RelationCandidate] | None = None,
) -> tuple[SourceRecord, ...]:
    """Attach the canonical relation ledger to immutable source records."""
    frozen = tuple(sources)
    derived = tuple(candidates) if candidates is not None else derive_explicit_relations(frozen)
    by_owner: dict[str, list[ExplicitRelation]] = {}
    for item in derived:
        by_owner.setdefault(item.owner_source_id, []).append(item.relation)
    known = {source.source_id for source in frozen}
    absent = sorted(set(by_owner) - known)
    if absent:
        raise ValueError(f"relation owner absent from frozen universe: {absent[0]}")
    return tuple(
        replace(
            source,
            explicit_relations=tuple(
                sorted(
                    by_owner.get(source.source_id, ()),
                    key=lambda relation: relation.relation_id.encode("utf-8"),
                )
            ),
        )
        for source in sorted(frozen, key=lambda item: item.source_id.encode("utf-8"))
    )


def validate_downstream_relation_references(
    sources: Iterable[SourceRecord],
    downstream_relations: Iterable[ExplicitRelation] = (),
    claims: Iterable[DocumentClaim] = (),
) -> dict[str, ExplicitRelation]:
    """Require one global owner, one target, and byte-identical downstream refs."""
    frozen_sources = tuple(sources)
    universe = {source.source_id for source in frozen_sources} | {
        claim.claim_id for claim in claims
    }
    ledger: dict[str, ExplicitRelation] = {}
    for source in frozen_sources:
        for relation in source.explicit_relations:
            if relation.target_source_id not in universe:
                raise ValueError(
                    f"relation target absent from frozen universe: {relation.target_source_id}"
                )
            if not relation.is_valid_for(source.source_id):
                raise ValueError(f"invalid relation_id: {relation.relation_id}")
            previous = ledger.get(relation.relation_id)
            if previous is not None:
                qualifier = " with different fields" if previous != relation else ""
                raise ValueError(f"duplicate relation_id: {relation.relation_id}{qualifier}")
            ledger[relation.relation_id] = relation
    for downstream in downstream_relations:
        resolved = ledger.get(downstream.relation_id)
        if resolved is None or resolved.to_dict() != downstream.to_dict():
            raise ValueError(
                f"downstream relation does not resolve byte-for-byte: {downstream.relation_id}"
            )
    return ledger
