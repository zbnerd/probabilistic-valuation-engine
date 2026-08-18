from __future__ import annotations

import gzip
import hashlib
import io
import json
import os
import tarfile

import pytest

from portfolio_builder.archive import PatchEntry, write_patch_volumes


def _members(volume):
    with tarfile.open(fileobj=io.BytesIO(volume.data), mode="r:gz") as archive:
        return {member.name: (member, archive.extractfile(member).read()) for member in archive}


def test_splits_at_exact_part_boundary_and_records_reassembly_manifest():
    content = b"a" * 8_000_000 + b"tail"
    entry = PatchEntry("GIT-" + "1" * 40 + "-P01", content)

    (volume,) = write_patch_volumes((entry,))
    members = _members(volume)
    stored = sorted(volume.members, key=lambda item: item.ordinal)

    assert [member.byte_count for member in stored] == [8_000_000, 4]
    assert b"".join(members[item.locator.split("#", 1)[1]][1] for item in stored) == content
    manifest = json.loads(members["reassembly-manifest.json"][1])
    row = manifest["entries"][0]
    assert row["source_id"] == entry.source_id
    assert row["whole_byte_count"] == len(content)
    assert row["whole_sha256"] == hashlib.sha256(content).hexdigest()
    assert [part["sha256"] for part in row["parts"]] == [
        hashlib.sha256(content[:8_000_000]).hexdigest(),
        hashlib.sha256(content[8_000_000:]).hexdigest(),
    ]


def test_archive_is_byte_deterministic_with_canonical_tar_and_gzip_metadata():
    entries = (
        PatchEntry("GIT-" + "b" * 40 + "-P01", b"second"),
        PatchEntry("GIT-" + "a" * 40 + "-ROOT", b"first"),
    )

    first = write_patch_volumes(entries)
    second = write_patch_volumes(tuple(reversed(entries)))

    assert [volume.data for volume in first] == [volume.data for volume in second]
    assert first[0].data[4:8] == b"\x00\x00\x00\x00"
    assert gzip.decompress(first[0].data)
    for member, _ in _members(first[0]).values():
        assert member.mtime == 0
        assert member.uid == 0
        assert member.gid == 0
        assert member.uname == ""
        assert member.gname == ""
        assert member.mode == 0o644


def test_groups_no_more_than_fifty_million_uncompressed_patch_bytes():
    entries = tuple(
        PatchEntry(f"GIT-{index:040x}-P01", bytes([index]) * 8_000_000)
        for index in range(7)
    )

    volumes = write_patch_volumes(entries)

    assert len(volumes) == 2
    assert all(volume.uncompressed_patch_bytes <= 50_000_000 for volume in volumes)
    assert sum(volume.uncompressed_patch_bytes for volume in volumes) == 56_000_000


def test_recursively_bisects_compressed_volumes_above_limit():
    entries = tuple(
        PatchEntry(f"GIT-{index:040x}-P01", os.urandom(12_000))
        for index in range(4)
    )

    volumes = write_patch_volumes(entries, max_bytes=30_000)

    assert len(volumes) >= 2
    assert all(volume.byte_count <= 30_000 for volume in volumes)
    assert sorted(member.member_id for volume in volumes for member in volume.members) == sorted(
        f"{entry.source_id}-part-001" for entry in entries
    )


def test_rejects_duplicate_ids_hash_mismatch_and_impossible_single_member_limit():
    source_id = "GIT-" + "1" * 40 + "-P01"
    with pytest.raises(ValueError, match="duplicate patch source_id"):
        write_patch_volumes((PatchEntry(source_id, b"a"), PatchEntry(source_id, b"b")))
    with pytest.raises(ValueError, match="stored hash"):
        write_patch_volumes((PatchEntry(source_id, b"a", stored_hash="0" * 64),))
    with pytest.raises(ValueError, match="single archive member"):
        write_patch_volumes((PatchEntry(source_id, os.urandom(2_000)),), max_bytes=200)
