import json

import pytest

from portfolio_builder.canonical_io import (
    read_jsonl,
    read_jsonl_with_descriptor,
    validate_relation_ledger,
    write_jsonl,
)
from portfolio_builder.jsonl_artifact import publish_jsonl_artifact
from portfolio_builder.models import (
    DocumentClaim,
    ExplicitRelation,
    SourceRecord,
    StoredArtifactMember,
)


def make_record(**overrides):
    values = {
        "source_id": "GIT-a" + "0" * 39 + "-ROOT",
        "source_type": "git-diff",
        "source_locator": "git:a",
        "snapshot_id": "snap-1",
        "title": "root",
        "evidence_scope": "project-evidence",
        "claim_authority": "primary-record",
        "recorded_status": "captured",
        "recorded_at": "2026-08-01T00:00:00Z",
        "raw_hash": "0" * 64,
        "stored_hash": "1" * 64,
        "raw_archive_locator": "commit-diffs-001.tar.gz#root.patch",
        "stored_members": (
            StoredArtifactMember(
                member_id="GIT-root-P01-part-001",
                locator="commit-diffs-001.tar.gz#root.patch",
                ordinal=1,
                total=1,
                byte_count=10,
                sha256="1" * 64,
            ),
        ),
        "explicit_relations": (),
        "case_ids": (),
        "classification": "unreviewed",
        "record_only_reason": None,
        "availability_status": "available",
        "privacy_redactions": (),
        "parse_status": "parsed",
        "payload": {"z": 1, "a": 2},
    }
    values.update(overrides)
    return SourceRecord(**values)


def make_claim(**overrides):
    values = {
        "claim_id": "claim-b",
        "document_source_id": "document-a",
        "source_path": "docs/evidence.md",
        "evidence_scope": "project-evidence",
        "claim_authority": "primary-record",
        "unit_kind": "paragraph",
        "line_start": 1,
        "line_end": 2,
        "page_index": None,
        "block_index": 1,
        "raw_hash": "3" * 64,
        "stored_hash": "4" * 64,
        "stored_members": (),
        "text": "captured evidence",
        "classification": "unreviewed",
        "parse_status": "parsed",
    }
    values.update(overrides)
    return DocumentClaim(**values)


def _canonical_bytes(record):
    return json.dumps(
        record.to_dict(), ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8") + b"\n"


def _whitespace_formatted_bytes(record):
    return json.dumps(
        record.to_dict(), ensure_ascii=False, sort_keys=True
    ).encode("utf-8") + b"\n"


def _duplicate_key_bytes(record):
    return _canonical_bytes(record).replace(
        b'"title":"root"', b'"title":"discarded","title":"root"'
    )


def test_source_record_round_trip_and_canonical_order(tmp_path):
    record = make_record()
    target = tmp_path / "records.jsonl"

    write_jsonl(target, [record])

    assert read_jsonl(target, SourceRecord) == [record]
    text = target.read_text(encoding="utf-8")
    assert text.endswith("\n") and not text.endswith("\n\n")
    assert text.index('"a"') < text.index('"z"')
    assert ": " not in text


def test_write_and_read_large_models_through_unchanged_logical_path(tmp_path):
    records = [
        make_record(source_id=f"source-{index}", stored_members=(), title="x" * 60)
        for index in range(3)
    ]
    target = tmp_path / "source_records.jsonl"

    written = write_jsonl(
        target,
        records,
        model_type=SourceRecord,
        target_bytes=300,
        max_compressed_bytes=10_000,
    )
    loaded, inspected = read_jsonl_with_descriptor(
        target,
        SourceRecord,
        max_compressed_bytes=10_000,
    )

    assert written.storage_mode == "canonical-jsonl-gzip-shards-v1"
    assert loaded == records
    assert inspected == written
    assert read_jsonl(target, SourceRecord, max_compressed_bytes=10_000) == records


def test_small_model_output_remains_byte_identical(tmp_path):
    record = make_record()
    target = tmp_path / "records.jsonl"
    expected = json.dumps(
        record.to_dict(), ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8") + b"\n"

    descriptor = write_jsonl(target, [record], model_type=SourceRecord)

    assert descriptor.storage_mode == "plain"
    assert target.read_bytes() == expected


@pytest.mark.parametrize(
    "line_factory", [_whitespace_formatted_bytes, _duplicate_key_bytes],
    ids=["whitespace-formatted", "duplicate-key"],
)
@pytest.mark.parametrize(
    "target_bytes, physical_name", [(10_000, "records.jsonl"), (32, "records-part-001.jsonl.gz")],
    ids=["plain", "sharded"],
)
def test_read_rejects_noncanonical_model_bytes_with_physical_path_and_line(
    tmp_path, line_factory, target_bytes, physical_name
):
    record = make_record(stored_members=())
    target = tmp_path / "records.jsonl"
    publish_jsonl_artifact(
        target,
        record_type="SourceRecord",
        records=[(record.source_id, line_factory(record))],
        target_bytes=target_bytes,
        max_compressed_bytes=10_000,
    )
    physical_path = target.parent / physical_name

    with pytest.raises(ValueError, match=rf"{physical_path}:1: record is not canonical"):
        read_jsonl(target, SourceRecord, max_compressed_bytes=10_000)


def test_relation_validation_spans_indexed_source_and_claim_ledgers(tmp_path):
    claim = make_claim(claim_id="claim-b", text="x" * 100)
    relation = ExplicitRelation.create(
        owner_source_id="source-a",
        relation_type="supports",
        target_source_id=claim.claim_id,
        evidence_locator="docs/evidence.md#L1-L2",
        evidence_hash=claim.raw_hash,
    )
    source = make_record(
        source_id="source-a",
        stored_members=(),
        explicit_relations=(relation,),
        title="x" * 100,
    )
    source_path = tmp_path / "source_records.jsonl"
    claim_path = tmp_path / "document_claim_inventory.jsonl"

    write_jsonl(
        source_path,
        [source],
        model_type=SourceRecord,
        claim_universe=[claim],
        target_bytes=32,
        max_compressed_bytes=10_000,
    )
    write_jsonl(
        claim_path,
        [claim],
        model_type=DocumentClaim,
        source_universe=[source],
        claim_universe=[claim],
        target_bytes=32,
        max_compressed_bytes=10_000,
    )

    assert read_jsonl(
        source_path,
        SourceRecord,
        claim_universe=[claim],
        max_compressed_bytes=10_000,
    ) == [source]
    assert read_jsonl(
        claim_path,
        DocumentClaim,
        source_universe=[source],
        claim_universe=[claim],
        max_compressed_bytes=10_000,
    ) == [claim]


def test_read_rejects_duplicate_nested_member_id_in_valid_artifact(tmp_path):
    member = StoredArtifactMember("member-1", "archive#a", 1, 1, 10, "1" * 64)
    records = [
        make_record(source_id="source-a", stored_members=(member,)),
        make_record(source_id="source-b", stored_members=(member,)),
    ]
    target = tmp_path / "records.jsonl"
    publish_jsonl_artifact(
        target,
        record_type="SourceRecord",
        records=((record.source_id, _canonical_bytes(record)) for record in records),
        target_bytes=32,
        max_compressed_bytes=10_000,
    )

    with pytest.raises(ValueError, match="duplicate member_id"):
        read_jsonl(target, SourceRecord, max_compressed_bytes=10_000)


def test_read_rejects_relation_target_absent_from_valid_artifact(tmp_path):
    relation = ExplicitRelation.create(
        owner_source_id="source-a",
        relation_type="supports",
        target_source_id="claim-b",
        evidence_locator="docs/evidence.md#L1-L2",
        evidence_hash="3" * 64,
    )
    source = make_record(
        source_id="source-a", stored_members=(), explicit_relations=(relation,)
    )
    target = tmp_path / "source_records.jsonl"
    publish_jsonl_artifact(
        target,
        record_type="SourceRecord",
        records=[(source.source_id, _canonical_bytes(source))],
        target_bytes=32,
        max_compressed_bytes=10_000,
    )

    with pytest.raises(ValueError, match="target absent"):
        read_jsonl(target, SourceRecord, max_compressed_bytes=10_000)


def test_read_rejects_changed_relation_in_frozen_universe(tmp_path):
    relation = ExplicitRelation.create(
        owner_source_id="source-a",
        relation_type="supports",
        target_source_id="source-b",
        evidence_locator="docs/evidence.md#L1-L2",
        evidence_hash="3" * 64,
    )
    frozen = make_record(
        source_id="source-a", stored_members=(), explicit_relations=(relation,)
    )
    changed = make_record(source_id="source-a", stored_members=())
    target_record = make_record(source_id="source-b", stored_members=())
    target = tmp_path / "source_records.jsonl"
    publish_jsonl_artifact(
        target,
        record_type="SourceRecord",
        records=[(changed.source_id, _canonical_bytes(changed))],
        target_bytes=32,
        max_compressed_bytes=10_000,
    )

    with pytest.raises(ValueError, match="record absent or changed"):
        read_jsonl(
            target,
            SourceRecord,
            source_universe=[frozen, target_record],
            max_compressed_bytes=10_000,
        )


def test_write_rejects_wrong_declared_model_type(tmp_path):
    with pytest.raises(ValueError, match="declared model type"):
        write_jsonl(tmp_path / "records.jsonl", [make_record()], model_type=DocumentClaim)


def test_write_typed_empty_ledger_as_zero_byte_plain_artifact(tmp_path):
    target = tmp_path / "records.jsonl"

    descriptor = write_jsonl(target, [], model_type=SourceRecord)

    assert descriptor.storage_mode == "plain"
    assert descriptor.record_count == 0
    assert target.read_bytes() == b""
    assert read_jsonl(target, SourceRecord) == []


def test_write_rejects_untyped_empty_ledger(tmp_path):
    with pytest.raises(ValueError, match="empty JSONL records require model_type"):
        write_jsonl(tmp_path / "records.jsonl", [])


def test_write_jsonl_replaces_atomically_from_sibling(tmp_path, monkeypatch):
    target = tmp_path / "records.jsonl"
    target.write_text("old\n", encoding="utf-8")
    replacements = []
    original_replace = type(target).replace

    def recording_replace(source, destination):
        replacements.append((source, destination))
        return original_replace(source, destination)

    monkeypatch.setattr(type(target), "replace", recording_replace)
    write_jsonl(target, [make_record()])

    assert replacements[-1][0].parent == target.parent
    assert replacements[-1][1] == target
    assert list(tmp_path.iterdir()) == [target]


def test_read_jsonl_reports_path_and_line(tmp_path):
    target = tmp_path / "records.jsonl"
    target.write_bytes(_canonical_bytes(make_record()) + b'{"broken"\n')

    with pytest.raises(ValueError, match=rf"{target}:2"):
        read_jsonl(target, SourceRecord)


def test_relation_id_is_canonical_and_owner_sensitive():
    relation = ExplicitRelation.create(
        owner_source_id="source-a",
        relation_type="references",
        target_source_id="source-b",
        evidence_locator="git:abc#L1",
        evidence_hash="2" * 64,
    )
    payload = json.dumps(
        {
            "source_id": "source-a",
            "relation_type": "references",
            "target_source_id": "source-b",
            "evidence_locator": "git:abc#L1",
            "evidence_hash": "2" * 64,
        },
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    import hashlib

    assert relation.relation_id == "REL-" + hashlib.sha256(payload.encode()).hexdigest()


def test_write_rejects_duplicate_source_ids(tmp_path):
    with pytest.raises(ValueError, match="duplicate source_id"):
        write_jsonl(tmp_path / "records.jsonl", [make_record(), make_record()])


def test_relation_ledger_rejects_absent_target_and_downstream_field_change():
    source_id = "source-a"
    relation = ExplicitRelation.create(
        owner_source_id=source_id,
        relation_type="references",
        target_source_id="source-b",
        evidence_locator="git:abc#L1",
        evidence_hash="2" * 64,
    )
    source = make_record(source_id=source_id, explicit_relations=(relation,))

    with pytest.raises(ValueError, match="target absent"):
        validate_relation_ledger([source])

    target = make_record(source_id="source-b", stored_members=())
    changed = ExplicitRelation(
        relation_id=relation.relation_id,
        relation_type=relation.relation_type,
        target_source_id=relation.target_source_id,
        evidence_locator="git:changed#L1",
        evidence_hash=relation.evidence_hash,
    )
    with pytest.raises(ValueError, match="byte-for-byte"):
        validate_relation_ledger([source, target], downstream_relations=[changed])


def test_write_accepts_relation_to_claim_in_explicit_frozen_universe(tmp_path):
    relation = ExplicitRelation.create(
        owner_source_id="source-a",
        relation_type="supports",
        target_source_id="claim-b",
        evidence_locator="docs/evidence.md#L1-L2",
        evidence_hash="3" * 64,
    )

    source = make_record(source_id="source-a", explicit_relations=(relation,))
    claim = make_claim()
    target = tmp_path / "source-ledger.jsonl"
    write_jsonl(
        target,
        [source],
        source_universe=[source],
        claim_universe=[claim],
    )
    assert read_jsonl(
        target,
        SourceRecord,
        source_universe=[source],
        claim_universe=[claim],
    ) == [source]


def test_sharded_relation_validation_accepts_separate_claim_universe(tmp_path):
    relation = ExplicitRelation.create(
        owner_source_id="source-a",
        relation_type="supports",
        target_source_id="claim-b",
        evidence_locator="docs/evidence.md#L1-L2",
        evidence_hash="3" * 64,
    )
    source_shard = [make_record(source_id="source-a", explicit_relations=(relation,))]
    claim_shard = [make_claim()]

    assert validate_relation_ledger(source_shard, claim_shard) == {
        relation.relation_id: relation
    }
    source_path = tmp_path / "source-shard.jsonl"
    write_jsonl(
        source_path,
        source_shard,
        source_universe=source_shard,
        claim_universe=claim_shard,
    )
    assert read_jsonl(
        source_path,
        SourceRecord,
        source_universe=source_shard,
        claim_universe=claim_shard,
    ) == source_shard


def test_write_rejects_exact_duplicate_nested_member_ids(tmp_path):
    member = StoredArtifactMember("member-1", "archive#a", 1, 1, 10, "1" * 64)
    records = [
        make_record(source_id="source-a", stored_members=(member,)),
        make_record(source_id="source-b", stored_members=(member,)),
    ]

    with pytest.raises(ValueError, match="duplicate member_id"):
        write_jsonl(tmp_path / "records.jsonl", records)


def test_write_rejects_conflicting_nested_member_fields(tmp_path):
    original = StoredArtifactMember("member-1", "archive#a", 1, 1, 10, "1" * 64)
    conflict = StoredArtifactMember("member-1", "archive#b", 1, 1, 11, "2" * 64)
    records = [
        make_record(source_id="source-a", stored_members=(original,)),
        make_record(source_id="source-b", stored_members=(conflict,)),
    ]

    with pytest.raises(ValueError, match="duplicate member_id.*different fields"):
        write_jsonl(tmp_path / "records.jsonl", records)


@pytest.mark.parametrize("conflicting", [False, True])
def test_relation_free_cross_shard_rejects_duplicate_top_level_ids(
    tmp_path, conflicting
):
    original = make_record(source_id="source-a", stored_members=())
    duplicate = make_record(
        source_id="source-a",
        stored_members=(),
        title="changed" if conflicting else original.title,
    )
    expected = "duplicate source_id.*different fields" if conflicting else "duplicate source_id"

    with pytest.raises(ValueError, match=expected):
        write_jsonl(
            tmp_path / "source-shard.jsonl",
            [duplicate],
            source_universe=[original, duplicate],
            claim_universe=[],
        )


@pytest.mark.parametrize("conflicting", [False, True])
def test_relation_free_cross_shard_rejects_duplicate_nested_member_ids(
    tmp_path, conflicting
):
    original_member = StoredArtifactMember(
        "member-1", "archive#a", 1, 1, 10, "1" * 64
    )
    duplicate_member = StoredArtifactMember(
        "member-1",
        "archive#b" if conflicting else original_member.locator,
        1,
        1,
        11 if conflicting else original_member.byte_count,
        "2" * 64 if conflicting else original_member.sha256,
    )
    source = make_record(
        source_id="source-a", stored_members=(original_member,), explicit_relations=()
    )
    claim = make_claim(stored_members=(duplicate_member,))
    expected = "duplicate member_id.*different fields" if conflicting else "duplicate member_id"

    with pytest.raises(ValueError, match=expected):
        write_jsonl(
            tmp_path / "source-shard.jsonl",
            [source],
            source_universe=[source],
            claim_universe=[claim],
        )


def test_write_rejects_heterogeneous_model_types(tmp_path):
    with pytest.raises(ValueError, match="homogeneous model type"):
        write_jsonl(
            tmp_path / "mixed.jsonl",
            [make_record(source_id="source-a"), make_claim()],
        )
