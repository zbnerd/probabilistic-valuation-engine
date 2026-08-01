"""Canonical, atomic JSON Lines serialization."""

from __future__ import annotations

import json
import os
import tempfile
from pathlib import Path
from typing import Protocol, TypeVar

from .models import DocumentClaim, ExplicitRelation, SourceRecord


class CanonicalModel(Protocol):
    def to_dict(self) -> dict[str, object]: ...


ModelT = TypeVar("ModelT")


def _identity(item: object) -> tuple[str, str] | None:
    for field in ("source_id", "claim_id", "relation_id", "member_id"):
        value = getattr(item, field, None)
        if isinstance(value, str):
            return field, value
    return None


def _validate(items: list[CanonicalModel]) -> None:
    seen: dict[tuple[str, str], object] = {}
    relation_ids: dict[str, object] = {}
    for item in items:
        identity = _identity(item)
        if identity is not None:
            if identity in seen:
                raise ValueError(f"duplicate {identity[0]}: {identity[1]}")
            seen[identity] = item
        if isinstance(item, SourceRecord):
            for relation in item.explicit_relations:
                if not relation.is_valid_for(item.source_id):
                    raise ValueError(f"invalid relation_id: {relation.relation_id}")
                previous = relation_ids.get(relation.relation_id)
                if previous is not None:
                    qualifier = "with different fields" if previous != relation else ""
                    raise ValueError(f"duplicate relation_id {relation.relation_id} {qualifier}".rstrip())
                relation_ids[relation.relation_id] = relation

    sources = [item for item in items if isinstance(item, SourceRecord)]
    if sources and any(source.explicit_relations for source in sources):
        validate_relation_ledger(sources)


def validate_relation_ledger(
    sources: list[SourceRecord] | tuple[SourceRecord, ...],
    claims: list[DocumentClaim] | tuple[DocumentClaim, ...] = (),
    downstream_relations: list[ExplicitRelation] | tuple[ExplicitRelation, ...] = (),
) -> dict[str, ExplicitRelation]:
    """Validate globally resolvable relations against a frozen source/claim universe."""
    universe: set[str] = set()
    for identity in [source.source_id for source in sources] + [claim.claim_id for claim in claims]:
        if identity in universe:
            raise ValueError(f"duplicate source/claim ID: {identity}")
        universe.add(identity)

    ledger: dict[str, ExplicitRelation] = {}
    for source in sources:
        for relation in source.explicit_relations:
            if not relation.is_valid_for(source.source_id):
                raise ValueError(f"invalid relation_id: {relation.relation_id}")
            if relation.relation_id in ledger:
                qualifier = (
                    " with different fields"
                    if ledger[relation.relation_id] != relation
                    else ""
                )
                raise ValueError(f"duplicate relation_id: {relation.relation_id}{qualifier}")
            if relation.target_source_id not in universe:
                raise ValueError(
                    f"relation target absent from frozen universe: {relation.target_source_id}"
                )
            ledger[relation.relation_id] = relation

    for downstream in downstream_relations:
        resolved = ledger.get(downstream.relation_id)
        if resolved is None or resolved.to_dict() != downstream.to_dict():
            raise ValueError(
                f"downstream relation does not resolve byte-for-byte: {downstream.relation_id}"
            )
    return ledger


def write_jsonl(path: str | Path, records) -> None:
    """Atomically write canonical UTF-8 JSONL to *path*."""
    target = Path(path)
    items = list(records)
    _validate(items)
    target.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{target.name}.", suffix=".tmp", dir=target.parent
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
            for item in items:
                json.dump(
                    item.to_dict(),
                    stream,
                    ensure_ascii=False,
                    sort_keys=True,
                    separators=(",", ":"),
                )
                stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)


def read_jsonl(path: str | Path, model_type: type[ModelT]) -> list[ModelT]:
    """Read canonical model records, identifying malformed input by path and line."""
    source = Path(path)
    records: list[ModelT] = []
    with source.open("r", encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, start=1):
            try:
                payload = json.loads(line)
                if not isinstance(payload, dict):
                    raise TypeError("record must be a JSON object")
                records.append(model_type.from_dict(payload))
            except (json.JSONDecodeError, KeyError, TypeError, ValueError) as error:
                raise ValueError(f"{source}:{line_number}: {error}") from error
    _validate(records)  # type: ignore[arg-type]
    return records
