from dataclasses import FrozenInstanceError

import pytest

from portfolio_builder.models import (
    AvailabilityStatus,
    Classification,
    ExternalInputFile,
    GitHubEndpointFingerprint,
    GitHubSnapshotWindow,
    RefSnapshot,
    SnapshotManifest,
    SourceBoundary,
)


def test_source_boundary_round_trip_is_immutable():
    boundary = SourceBoundary(
        schema_version=1,
        source_snapshot_head="0" * 40,
        source_snapshot_tree="1" * 40,
        first_excluded_commit="2" * 40,
        first_excluded_parent="3" * 40,
        workflow_ref="refs/heads/docs/test",
        external_input_files=(
            ExternalInputFile("renewal-guide", "guide.pdf", 12, "4" * 64),
        ),
        legacy_owned_outputs=(),
    )

    assert SourceBoundary.from_dict(boundary.to_dict()) == boundary
    with pytest.raises(FrozenInstanceError):
        boundary.schema_version = 2


def test_snapshot_manifest_round_trip_preserves_nested_tuples():
    endpoint = GitHubEndpointFingerprint(
        item_key="pr:1",
        endpoint_key="comments",
        request_params_sha256="5" * 64,
        accept="application/vnd.github+json",
        page_numbers=(1, 2),
        page_response_hashes=("6" * 64, "7" * 64),
        stable_child_ids=("11", "12"),
        availability_status="available",
    )
    window = GitHubSnapshotWindow(
        enumeration_started_at="2026-08-01T00:00:00Z",
        enumeration_completed_at="2026-08-01T00:01:00Z",
        reconciled_at="2026-08-01T00:02:00Z",
        pull_request_numbers=(1,),
        issue_numbers=(2,),
        updated_at_by_item={"pr:1": "2026-08-01T00:00:30Z"},
        endpoint_fingerprints=(endpoint,),
    )
    manifest = SnapshotManifest(
        snapshot_id="snap-1",
        started_at="2026-08-01T00:00:00Z",
        local_completed_at="2026-08-01T00:00:10Z",
        finalized_at=None,
        source_boundary_sha256="8" * 64,
        source_snapshot_head="0" * 40,
        source_snapshot_tree="1" * 40,
        first_excluded_commit="2" * 40,
        first_excluded_parent="3" * 40,
        workflow_ref="refs/heads/docs/test",
        observed_head_sha="0" * 40,
        observed_head_symbolic_target="refs/heads/docs/test",
        observed_refs=(RefSnapshot("HEAD", "0" * 40, "commit", None, None, None),),
        semantic_refs=(),
        excluded_workflow_commit_shas_at_capture=("2" * 40,),
        external_input_files=(),
        legacy_owned_outputs=(),
        tracked_files=(),
        ai_trace_files=(),
        github_window=window,
    )

    assert SnapshotManifest.from_dict(manifest.to_dict()) == manifest
    assert manifest.to_dict()["observed_refs"][0]["refname"] == "HEAD"


def test_status_enums_have_locked_values():
    assert AvailabilityStatus.AVAILABLE == "available"
    assert AvailabilityStatus.CONFIRMED_UNAVAILABLE == "confirmed-unavailable"
    assert AvailabilityStatus.TRANSIENT_FAILURE == "transient-failure"
    assert Classification.UNREVIEWED == "unreviewed"
    assert Classification.CASE == "case"
    assert Classification.RECORD_ONLY == "record-only"
