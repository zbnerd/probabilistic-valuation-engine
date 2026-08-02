"""Canonical, atomic JSON Lines serialization."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Iterable, Protocol, TypeVar

from .jsonl_artifact import (
    DEFAULT_MAX_COMPRESSED_BYTES,
    DEFAULT_TARGET_BYTES,
    JsonlArtifactDescriptor,
    publish_jsonl_artifact,
    read_jsonl_artifact,
)
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


def _register_identity(
    seen: dict[str, tuple[str, object]], field: str, identity: str, item: object
) -> None:
    previous = seen.get(identity)
    if previous is None:
        seen[identity] = (field, item)
        return
    qualifier = " with different fields" if previous[1] != item else ""
    raise ValueError(f"duplicate {field}: {identity}{qualifier}")


def _validate_identity_ledger(items: list[CanonicalModel]) -> None:
    seen: dict[str, tuple[str, object]] = {}
    for item in items:
        identity = _identity(item)
        if identity is not None:
            _register_identity(seen, identity[0], identity[1], item)
        if isinstance(item, SourceRecord | DocumentClaim):
            for member in item.stored_members:
                _register_identity(seen, "member_id", member.member_id, member)
        if isinstance(item, SourceRecord):
            for relation in item.explicit_relations:
                _register_identity(seen, "relation_id", relation.relation_id, relation)
                if not relation.is_valid_for(item.source_id):
                    raise ValueError(f"invalid relation_id: {relation.relation_id}")


def _validate(
    items: list[CanonicalModel],
    source_universe: list[SourceRecord] | tuple[SourceRecord, ...] | None = None,
    claim_universe: list[DocumentClaim] | tuple[DocumentClaim, ...] | None = None,
) -> None:
    _validate_identity_ledger(items)

    local_sources = [item for item in items if isinstance(item, SourceRecord)]
    local_claims = [item for item in items if isinstance(item, DocumentClaim)]
    frozen_sources = list(source_universe) if source_universe is not None else local_sources
    frozen_claims = list(claim_universe) if claim_universe is not None else local_claims
    validate_relation_ledger(frozen_sources, frozen_claims)

    frozen_by_id: dict[str, object] = {
        **{source.source_id: source for source in frozen_sources},
        **{claim.claim_id: claim for claim in frozen_claims},
    }
    for item in [*local_sources, *local_claims]:
        identity = item.source_id if isinstance(item, SourceRecord) else item.claim_id
        if frozen_by_id.get(identity) != item:
            raise ValueError(f"record absent or changed in frozen universe: {identity}")


def validate_relation_ledger(
    sources: list[SourceRecord] | tuple[SourceRecord, ...],
    claims: list[DocumentClaim] | tuple[DocumentClaim, ...] = (),
    downstream_relations: list[ExplicitRelation] | tuple[ExplicitRelation, ...] = (),
) -> dict[str, ExplicitRelation]:
    """Validate globally resolvable relations against a frozen source/claim universe."""
    _validate_identity_ledger([*sources, *claims])
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


def _canonical_line(item: CanonicalModel) -> bytes:
    return json.dumps(
        item.to_dict(), ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8") + b"\n"


def _required_identity(item: object) -> str:
    identity = _identity(item)
    if identity is None:
        raise ValueError(f"canonical model has no stable identity: {type(item).__name__}")
    return identity[1]


def write_jsonl(
    path: str | Path,
    records: Iterable[CanonicalModel],
    *,
    model_type: type[CanonicalModel] | None = None,
    source_universe: list[SourceRecord] | tuple[SourceRecord, ...] | None = None,
    claim_universe: list[DocumentClaim] | tuple[DocumentClaim, ...] | None = None,
    target_bytes: int = DEFAULT_TARGET_BYTES,
    max_compressed_bytes: int = DEFAULT_MAX_COMPRESSED_BYTES,
) -> JsonlArtifactDescriptor:
    """Publish canonical model records through the physical artifact layer."""
    items = list(records)
    model_types = {type(item) for item in items}
    if len(model_types) > 1:
        names = ", ".join(sorted(model_type.__name__ for model_type in model_types))
        raise ValueError(f"JSONL records must have one homogeneous model type; got: {names}")
    if model_type is None:
        if not items:
            raise ValueError("empty JSONL records require model_type")
        model_type = next(iter(model_types))
    if any(type(item) is not model_type for item in items):
        raise ValueError(
            f"declared model type {model_type.__name__} differs from record model type"
        )
    _validate(items, source_universe, claim_universe)
    return publish_jsonl_artifact(
        path,
        record_type=model_type.__name__,
        records=(
            (_required_identity(item), _canonical_line(item)) for item in items
        ),
        target_bytes=target_bytes,
        max_compressed_bytes=max_compressed_bytes,
    )


def read_jsonl_with_descriptor(
    path: str | Path,
    model_type: type[ModelT],
    *,
    source_universe: list[SourceRecord] | tuple[SourceRecord, ...] | None = None,
    claim_universe: list[DocumentClaim] | tuple[DocumentClaim, ...] | None = None,
    max_compressed_bytes: int = DEFAULT_MAX_COMPRESSED_BYTES,
) -> tuple[list[ModelT], JsonlArtifactDescriptor]:
    """Read and validate canonical models after physical artifact validation."""
    records: list[ModelT] = []

    def consume(physical_path: Path, line_number: int, line: bytes) -> None:
        try:
            payload = json.loads(line.decode("utf-8"))
            if not isinstance(payload, dict):
                raise TypeError("record must be a JSON object")
            records.append(model_type.from_dict(payload))
        except (UnicodeDecodeError, json.JSONDecodeError, KeyError, TypeError, ValueError) as error:
            raise ValueError(f"{physical_path}:{line_number}: {error}") from error

    descriptor = read_jsonl_artifact(
        path,
        expected_record_type=model_type.__name__,
        consume=consume,
        max_compressed_bytes=max_compressed_bytes,
    )
    _validate(records, source_universe, claim_universe)  # type: ignore[arg-type]
    return records, descriptor


def read_jsonl(
    path: str | Path,
    model_type: type[ModelT],
    *,
    source_universe: list[SourceRecord] | tuple[SourceRecord, ...] | None = None,
    claim_universe: list[DocumentClaim] | tuple[DocumentClaim, ...] | None = None,
    max_compressed_bytes: int = DEFAULT_MAX_COMPRESSED_BYTES,
) -> list[ModelT]:
    """Read canonical model records through the physical artifact layer."""
    return read_jsonl_with_descriptor(
        path,
        model_type,
        source_universe=source_universe,
        claim_universe=claim_universe,
        max_compressed_bytes=max_compressed_bytes,
    )[0]
