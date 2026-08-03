"""Immutable, JSON-compatible records used by the evidence pipeline."""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from enum import StrEnum
from typing import Literal, Mapping


JsonMapping = Mapping[str, object]


def _tuple_of(model_type, values: object) -> tuple:
    if not isinstance(values, list | tuple):
        raise TypeError(f"expected a sequence for {model_type.__name__}")
    return tuple(model_type.from_dict(value) for value in values)


def _string_tuple(values: object) -> tuple[str, ...]:
    if not isinstance(values, list | tuple):
        raise TypeError("expected a sequence of strings")
    if not all(isinstance(value, str) for value in values):
        raise TypeError("expected a sequence of strings")
    return tuple(values)


class AvailabilityStatus(StrEnum):
    AVAILABLE = "available"
    CONFIRMED_UNAVAILABLE = "confirmed-unavailable"
    TRANSIENT_FAILURE = "transient-failure"


class Classification(StrEnum):
    UNREVIEWED = "unreviewed"
    CASE = "case"
    RECORD_ONLY = "record-only"


@dataclass(frozen=True, slots=True)
class StoredArtifactMember:
    member_id: str
    locator: str
    ordinal: int
    total: int
    byte_count: int
    sha256: str

    def to_dict(self) -> dict[str, object]:
        return {
            "member_id": self.member_id,
            "locator": self.locator,
            "ordinal": self.ordinal,
            "total": self.total,
            "byte_count": self.byte_count,
            "sha256": self.sha256,
        }

    @classmethod
    def from_dict(cls, value: JsonMapping) -> StoredArtifactMember:
        return cls(**value)


@dataclass(frozen=True, slots=True)
class ExplicitRelation:
    relation_id: str
    relation_type: str
    target_source_id: str
    evidence_locator: str
    evidence_hash: str

    @staticmethod
    def identifier(
        owner_source_id: str,
        relation_type: str,
        target_source_id: str,
        evidence_locator: str,
        evidence_hash: str,
    ) -> str:
        encoded = json.dumps(
            {
                "source_id": owner_source_id,
                "relation_type": relation_type,
                "target_source_id": target_source_id,
                "evidence_locator": evidence_locator,
                "evidence_hash": evidence_hash,
            },
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
        return "REL-" + hashlib.sha256(encoded).hexdigest()

    @classmethod
    def create(
        cls,
        *,
        owner_source_id: str,
        relation_type: str,
        target_source_id: str,
        evidence_locator: str,
        evidence_hash: str,
    ) -> ExplicitRelation:
        return cls(
            relation_id=cls.identifier(
                owner_source_id,
                relation_type,
                target_source_id,
                evidence_locator,
                evidence_hash,
            ),
            relation_type=relation_type,
            target_source_id=target_source_id,
            evidence_locator=evidence_locator,
            evidence_hash=evidence_hash,
        )

    def is_valid_for(self, owner_source_id: str) -> bool:
        return self.relation_id == self.identifier(
            owner_source_id,
            self.relation_type,
            self.target_source_id,
            self.evidence_locator,
            self.evidence_hash,
        )

    def to_dict(self) -> dict[str, object]:
        return {
            "relation_id": self.relation_id,
            "relation_type": self.relation_type,
            "target_source_id": self.target_source_id,
            "evidence_locator": self.evidence_locator,
            "evidence_hash": self.evidence_hash,
        }

    @classmethod
    def from_dict(cls, value: JsonMapping) -> ExplicitRelation:
        return cls(**value)


@dataclass(frozen=True, slots=True)
class RefSnapshot:
    refname: str
    object_sha: str
    object_type: str
    peeled_sha: str | None
    peeled_type: str | None
    symbolic_target: str | None

    def to_dict(self) -> dict[str, object]:
        return {
            "refname": self.refname,
            "object_sha": self.object_sha,
            "object_type": self.object_type,
            "peeled_sha": self.peeled_sha,
            "peeled_type": self.peeled_type,
            "symbolic_target": self.symbolic_target,
        }

    @classmethod
    def from_dict(cls, value: JsonMapping) -> RefSnapshot:
        return cls(**value)


@dataclass(frozen=True, slots=True)
class ExternalInputFile:
    role: Literal["renewal-guide", "id-photo-source-resume", "legacy-portfolio-reference"]
    path: str
    byte_count: int
    sha256: str

    def to_dict(self) -> dict[str, object]:
        return {
            "role": self.role,
            "path": self.path,
            "byte_count": self.byte_count,
            "sha256": self.sha256,
        }

    @classmethod
    def from_dict(cls, value: JsonMapping) -> ExternalInputFile:
        return cls(**value)


@dataclass(frozen=True, slots=True)
class LegacyOwnedOutput:
    path: str
    git_blob_oid: str
    sha256: str

    def to_dict(self) -> dict[str, object]:
        return {
            "path": self.path,
            "git_blob_oid": self.git_blob_oid,
            "sha256": self.sha256,
        }

    @classmethod
    def from_dict(cls, value: JsonMapping) -> LegacyOwnedOutput:
        return cls(**value)


@dataclass(frozen=True, slots=True)
class SourceBoundary:
    schema_version: int
    source_snapshot_head: str
    source_snapshot_tree: str
    first_excluded_commit: str
    first_excluded_parent: str
    workflow_ref: str
    external_input_files: tuple[ExternalInputFile, ...]
    legacy_owned_outputs: tuple[LegacyOwnedOutput, ...]

    def to_dict(self) -> dict[str, object]:
        return {
            "schema_version": self.schema_version,
            "source_snapshot_head": self.source_snapshot_head,
            "source_snapshot_tree": self.source_snapshot_tree,
            "first_excluded_commit": self.first_excluded_commit,
            "first_excluded_parent": self.first_excluded_parent,
            "workflow_ref": self.workflow_ref,
            "external_input_files": [value.to_dict() for value in self.external_input_files],
            "legacy_owned_outputs": [value.to_dict() for value in self.legacy_owned_outputs],
        }

    @classmethod
    def from_dict(cls, value: JsonMapping) -> SourceBoundary:
        fields = dict(value)
        fields["external_input_files"] = _tuple_of(
            ExternalInputFile, fields["external_input_files"]
        )
        fields["legacy_owned_outputs"] = _tuple_of(
            LegacyOwnedOutput, fields["legacy_owned_outputs"]
        )
        return cls(**fields)


@dataclass(frozen=True, slots=True)
class FileSnapshot:
    path: str
    byte_count: int
    sha256: str

    def to_dict(self) -> dict[str, object]:
        return {"path": self.path, "byte_count": self.byte_count, "sha256": self.sha256}

    @classmethod
    def from_dict(cls, value: JsonMapping) -> FileSnapshot:
        return cls(**value)


@dataclass(frozen=True, slots=True)
class TrackedFileSnapshot:
    path: str
    git_mode: str
    object_type: str
    object_sha: str
    collection_rule_id: Literal["document", "ai-trace", "non-document"]

    def to_dict(self) -> dict[str, object]:
        return {
            "path": self.path,
            "git_mode": self.git_mode,
            "object_type": self.object_type,
            "object_sha": self.object_sha,
            "collection_rule_id": self.collection_rule_id,
        }

    @classmethod
    def from_dict(cls, value: JsonMapping) -> TrackedFileSnapshot:
        return cls(**value)


@dataclass(frozen=True, slots=True)
class GitHubEndpointFingerprint:
    item_key: str
    endpoint_key: str
    request_params_sha256: str
    accept: str
    page_numbers: tuple[int, ...]
    page_response_hashes: tuple[str, ...]
    stable_child_ids: tuple[str, ...]
    availability_status: str

    def to_dict(self) -> dict[str, object]:
        return {
            "item_key": self.item_key,
            "endpoint_key": self.endpoint_key,
            "request_params_sha256": self.request_params_sha256,
            "accept": self.accept,
            "page_numbers": list(self.page_numbers),
            "page_response_hashes": list(self.page_response_hashes),
            "stable_child_ids": list(self.stable_child_ids),
            "availability_status": self.availability_status,
        }

    @classmethod
    def from_dict(cls, value: JsonMapping) -> GitHubEndpointFingerprint:
        fields = dict(value)
        fields["page_numbers"] = tuple(fields["page_numbers"])
        fields["page_response_hashes"] = _string_tuple(fields["page_response_hashes"])
        fields["stable_child_ids"] = _string_tuple(fields["stable_child_ids"])
        return cls(**fields)


@dataclass(frozen=True, slots=True)
class GitHubSnapshotWindow:
    enumeration_started_at: str
    enumeration_completed_at: str
    reconciled_at: str
    pull_request_numbers: tuple[int, ...]
    issue_numbers: tuple[int, ...]
    updated_at_by_item: dict[str, str]
    endpoint_fingerprints: tuple[GitHubEndpointFingerprint, ...]

    def to_dict(self) -> dict[str, object]:
        return {
            "enumeration_started_at": self.enumeration_started_at,
            "enumeration_completed_at": self.enumeration_completed_at,
            "reconciled_at": self.reconciled_at,
            "pull_request_numbers": list(self.pull_request_numbers),
            "issue_numbers": list(self.issue_numbers),
            "updated_at_by_item": dict(self.updated_at_by_item),
            "endpoint_fingerprints": [value.to_dict() for value in self.endpoint_fingerprints],
        }

    @classmethod
    def from_dict(cls, value: JsonMapping) -> GitHubSnapshotWindow:
        fields = dict(value)
        fields["pull_request_numbers"] = tuple(fields["pull_request_numbers"])
        fields["issue_numbers"] = tuple(fields["issue_numbers"])
        fields["updated_at_by_item"] = dict(fields["updated_at_by_item"])
        fields["endpoint_fingerprints"] = _tuple_of(
            GitHubEndpointFingerprint, fields["endpoint_fingerprints"]
        )
        return cls(**fields)


@dataclass(frozen=True, slots=True)
class SnapshotManifest:
    snapshot_id: str
    started_at: str
    local_completed_at: str
    finalized_at: str | None
    source_boundary_sha256: str
    source_snapshot_head: str
    source_snapshot_tree: str
    first_excluded_commit: str
    first_excluded_parent: str
    workflow_ref: str
    observed_head_sha: str
    observed_head_symbolic_target: str | None
    observed_refs: tuple[RefSnapshot, ...]
    semantic_refs: tuple[RefSnapshot, ...]
    excluded_workflow_commit_shas_at_capture: tuple[str, ...]
    external_input_files: tuple[ExternalInputFile, ...]
    legacy_owned_outputs: tuple[LegacyOwnedOutput, ...]
    tracked_files: tuple[TrackedFileSnapshot, ...]
    ai_trace_files: tuple[FileSnapshot, ...]
    github_window: GitHubSnapshotWindow | None

    def to_dict(self) -> dict[str, object]:
        return {
            "snapshot_id": self.snapshot_id,
            "started_at": self.started_at,
            "local_completed_at": self.local_completed_at,
            "finalized_at": self.finalized_at,
            "source_boundary_sha256": self.source_boundary_sha256,
            "source_snapshot_head": self.source_snapshot_head,
            "source_snapshot_tree": self.source_snapshot_tree,
            "first_excluded_commit": self.first_excluded_commit,
            "first_excluded_parent": self.first_excluded_parent,
            "workflow_ref": self.workflow_ref,
            "observed_head_sha": self.observed_head_sha,
            "observed_head_symbolic_target": self.observed_head_symbolic_target,
            "observed_refs": [value.to_dict() for value in self.observed_refs],
            "semantic_refs": [value.to_dict() for value in self.semantic_refs],
            "excluded_workflow_commit_shas_at_capture": list(
                self.excluded_workflow_commit_shas_at_capture
            ),
            "external_input_files": [value.to_dict() for value in self.external_input_files],
            "legacy_owned_outputs": [value.to_dict() for value in self.legacy_owned_outputs],
            "tracked_files": [value.to_dict() for value in self.tracked_files],
            "ai_trace_files": [value.to_dict() for value in self.ai_trace_files],
            "github_window": self.github_window.to_dict() if self.github_window else None,
        }

    @classmethod
    def from_dict(cls, value: JsonMapping) -> SnapshotManifest:
        fields = dict(value)
        fields["observed_refs"] = _tuple_of(RefSnapshot, fields["observed_refs"])
        fields["semantic_refs"] = _tuple_of(RefSnapshot, fields["semantic_refs"])
        fields["excluded_workflow_commit_shas_at_capture"] = _string_tuple(
            fields["excluded_workflow_commit_shas_at_capture"]
        )
        fields["external_input_files"] = _tuple_of(
            ExternalInputFile, fields["external_input_files"]
        )
        fields["legacy_owned_outputs"] = _tuple_of(
            LegacyOwnedOutput, fields["legacy_owned_outputs"]
        )
        fields["tracked_files"] = _tuple_of(TrackedFileSnapshot, fields["tracked_files"])
        fields["ai_trace_files"] = _tuple_of(FileSnapshot, fields["ai_trace_files"])
        if fields["github_window"] is not None:
            fields["github_window"] = GitHubSnapshotWindow.from_dict(fields["github_window"])
        return cls(**fields)


@dataclass(frozen=True, slots=True)
class DocumentClaim:
    claim_id: str
    document_source_id: str
    source_path: str
    evidence_scope: Literal["project-evidence", "personal-evidence", "structural-reference"]
    claim_authority: Literal[
        "primary-record",
        "personal-record",
        "structural-reference",
        "ai-assertion",
        "trace-observation",
        "legacy-derived-record",
    ]
    unit_kind: str
    line_start: int | None
    line_end: int | None
    page_index: int | None
    block_index: int
    raw_hash: str
    stored_hash: str
    stored_members: tuple[StoredArtifactMember, ...]
    text: str
    classification: str
    parse_status: str

    def to_dict(self) -> dict[str, object]:
        return {
            "claim_id": self.claim_id,
            "document_source_id": self.document_source_id,
            "source_path": self.source_path,
            "evidence_scope": self.evidence_scope,
            "claim_authority": self.claim_authority,
            "unit_kind": self.unit_kind,
            "line_start": self.line_start,
            "line_end": self.line_end,
            "page_index": self.page_index,
            "block_index": self.block_index,
            "raw_hash": self.raw_hash,
            "stored_hash": self.stored_hash,
            "stored_members": [value.to_dict() for value in self.stored_members],
            "text": self.text,
            "classification": self.classification,
            "parse_status": self.parse_status,
        }

    @classmethod
    def from_dict(cls, value: JsonMapping) -> DocumentClaim:
        fields = dict(value)
        fields["stored_members"] = _tuple_of(StoredArtifactMember, fields["stored_members"])
        return cls(**fields)


@dataclass(frozen=True, slots=True)
class SourceRecord:
    source_id: str
    source_type: str
    source_locator: str
    snapshot_id: str
    title: str
    evidence_scope: Literal["project-evidence", "personal-evidence", "structural-reference"]
    claim_authority: Literal[
        "primary-record",
        "personal-record",
        "structural-reference",
        "ai-assertion",
        "trace-observation",
        "legacy-derived-record",
    ]
    recorded_status: str
    recorded_at: str | None
    raw_hash: str
    stored_hash: str
    raw_archive_locator: str | None
    stored_members: tuple[StoredArtifactMember, ...]
    explicit_relations: tuple[ExplicitRelation, ...]
    case_ids: tuple[str, ...]
    classification: str
    record_only_reason: str | None
    availability_status: str
    privacy_redactions: tuple[str, ...]
    parse_status: str
    payload: dict[str, object]

    def to_dict(self) -> dict[str, object]:
        return {
            "source_id": self.source_id,
            "source_type": self.source_type,
            "source_locator": self.source_locator,
            "snapshot_id": self.snapshot_id,
            "title": self.title,
            "evidence_scope": self.evidence_scope,
            "claim_authority": self.claim_authority,
            "recorded_status": self.recorded_status,
            "recorded_at": self.recorded_at,
            "raw_hash": self.raw_hash,
            "stored_hash": self.stored_hash,
            "raw_archive_locator": self.raw_archive_locator,
            "stored_members": [value.to_dict() for value in self.stored_members],
            "explicit_relations": [value.to_dict() for value in self.explicit_relations],
            "case_ids": list(self.case_ids),
            "classification": self.classification,
            "record_only_reason": self.record_only_reason,
            "availability_status": self.availability_status,
            "privacy_redactions": list(self.privacy_redactions),
            "parse_status": self.parse_status,
            "payload": self.payload,
        }

    @classmethod
    def from_dict(cls, value: JsonMapping) -> SourceRecord:
        fields = dict(value)
        fields["stored_members"] = _tuple_of(StoredArtifactMember, fields["stored_members"])
        fields["explicit_relations"] = _tuple_of(ExplicitRelation, fields["explicit_relations"])
        fields["case_ids"] = _string_tuple(fields["case_ids"])
        fields["privacy_redactions"] = _string_tuple(fields["privacy_redactions"])
        fields["payload"] = dict(fields["payload"])
        return cls(**fields)
