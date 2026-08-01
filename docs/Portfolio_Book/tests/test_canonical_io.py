import json

import pytest

from portfolio_builder.canonical_io import (
    read_jsonl,
    validate_relation_ledger,
    write_jsonl,
)
from portfolio_builder.models import ExplicitRelation, SourceRecord, StoredArtifactMember


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


def test_source_record_round_trip_and_canonical_order(tmp_path):
    record = make_record()
    target = tmp_path / "records.jsonl"

    write_jsonl(target, [record])

    assert read_jsonl(target, SourceRecord) == [record]
    text = target.read_text(encoding="utf-8")
    assert text.endswith("\n") and not text.endswith("\n\n")
    assert text.index('"a"') < text.index('"z"')
    assert ": " not in text


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

    assert len(replacements) == 1
    assert replacements[0][0].parent == target.parent
    assert replacements[0][1] == target
    assert list(tmp_path.iterdir()) == [target]


def test_read_jsonl_reports_path_and_line(tmp_path):
    target = tmp_path / "records.jsonl"
    valid = json.dumps(make_record().to_dict(), ensure_ascii=False, sort_keys=True)
    target.write_text(valid + '\n{"broken"\n', encoding="utf-8")

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

    target = make_record(source_id="source-b")
    changed = ExplicitRelation(
        relation_id=relation.relation_id,
        relation_type=relation.relation_type,
        target_source_id=relation.target_source_id,
        evidence_locator="git:changed#L1",
        evidence_hash=relation.evidence_hash,
    )
    with pytest.raises(ValueError, match="byte-for-byte"):
        validate_relation_ledger([source, target], downstream_relations=[changed])
