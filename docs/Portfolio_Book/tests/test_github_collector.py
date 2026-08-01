import hashlib
import json
import tarfile
from dataclasses import dataclass

import pytest

from portfolio_builder.github_client import GitHubClientError, GitHubPage
from portfolio_builder.github_collector import (
    collect_issues,
    collect_pull_requests,
    reconcile_github,
)


def _body(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode()


def _page(path, value, page=1, *, status="available"):
    body = _body(value)
    return GitHubPage(
        endpoint=path,
        params={"page": page} if page > 1 else {},
        page_number=page,
        body=body,
        json=value,
        response_hash=hashlib.sha256(body).hexdigest(),
        availability_status=status,
    )


class StaticClient:
    def __init__(self, pages, bytes_by_path=None):
        self.pages = pages
        self.bytes_by_path = bytes_by_path or {}
        self.calls = []

    def get_pages(self, path, params=None, accept="application/vnd.github+json"):
        self.calls.append(("pages", path, dict(params or {}), accept))
        value = self.pages.get(path, ())
        if isinstance(value, Exception):
            raise value
        return tuple(value)

    def get_bytes(self, path, params=None, accept="application/vnd.github+json"):
        self.calls.append(("bytes", path, dict(params or {}), accept))
        return self.bytes_by_path.get(path)


def _pr_pages(number, *, updated_at="2026-01-01T00:00:00Z", review_version=1):
    base = f"/repos/zbnerd/probabilistic-valuation-engine"
    detail = {
        "id": 1000 + number,
        "number": number,
        "title": f"PR {number}",
        "state": "closed",
        "updated_at": updated_at,
        "commits": 1,
        "changed_files": 1,
        "review_comments": 2,
        "comments": 2,
    }
    return {
        f"{base}/pulls/{number}": (_page(f"{base}/pulls/{number}", detail),),
        f"{base}/pulls/{number}/commits": (
            _page(f"{base}/pulls/{number}/commits", [{"sha": f"sha-{number}"}]),
        ),
        f"{base}/pulls/{number}/files": (
            _page(f"{base}/pulls/{number}/files", [{"sha": f"blob-{number}", "filename": "a.py"}]),
        ),
        f"{base}/pulls/{number}/reviews": (
            _page(f"{base}/pulls/{number}/reviews", [{"id": 10 + review_version}]),
            _page(f"{base}/pulls/{number}/reviews", [{"id": 20 + review_version}], 2),
        ),
        f"{base}/pulls/{number}/comments": (
            _page(f"{base}/pulls/{number}/comments", [{"id": 30 + review_version}]),
            _page(f"{base}/pulls/{number}/comments", [{"id": 40 + review_version}], 2),
        ),
        f"{base}/issues/{number}/comments": (
            _page(f"{base}/issues/{number}/comments", [{"id": 50 + review_version}]),
            _page(f"{base}/issues/{number}/comments", [{"id": 60 + review_version}], 2),
        ),
        f"{base}/issues/{number}/timeline": (
            _page(f"{base}/issues/{number}/timeline", [{"id": 70 + review_version, "event": "review_requested"}]),
        ),
        f"{base}/issues/{number}/reactions": (
            _page(f"{base}/issues/{number}/reactions", [{"id": 80 + review_version}]),
            _page(f"{base}/issues/{number}/reactions", [{"id": 90 + review_version}], 2),
        ),
        f"{base}/pulls/{number}/requested_reviewers": (
            _page(
                f"{base}/pulls/{number}/requested_reviewers",
                {"users": [{"id": 100 + review_version, "login": "reviewer"}], "teams": []},
            ),
        ),
    }


def _issue_pages(number, *, updated_at="2026-01-01T00:00:00Z"):
    base = "/repos/zbnerd/probabilistic-valuation-engine"
    detail = {
        "id": 2000 + number,
        "number": number,
        "title": f"Issue {number}",
        "state": "closed",
        "updated_at": updated_at,
        "comments": 1,
    }
    return {
        f"{base}/issues/{number}": (_page(f"{base}/issues/{number}", detail),),
        f"{base}/issues/{number}/comments": (
            _page(f"{base}/issues/{number}/comments", [{"id": 3000 + number}]),
        ),
        f"{base}/issues/{number}/timeline": (
            _page(f"{base}/issues/{number}/timeline", [{"id": 4000 + number, "event": "closed"}]),
        ),
        f"{base}/issues/{number}/reactions": (
            _page(f"{base}/issues/{number}/reactions", [{"id": 5000 + number}]),
        ),
    }


def test_collectors_walk_all_pages_filter_pr_rows_and_link_every_child(tmp_path):
    base = "/repos/zbnerd/probabilistic-valuation-engine"
    pages = {
        f"{base}/pulls": (
            _page(f"{base}/pulls", [{"number": 1, "updated_at": "u1"}]),
            _page(f"{base}/pulls", [{"number": 2, "updated_at": "u2"}], 2),
        ),
        f"{base}/issues": (
            _page(f"{base}/issues", [{"number": 1, "pull_request": {}}, {"number": 3, "updated_at": "u3"}]),
            _page(f"{base}/issues", [{"number": 4, "updated_at": "u4"}], 2),
        ),
        **_pr_pages(1),
        **_pr_pages(2),
        **_issue_pages(3),
        **_issue_pages(4),
    }
    patches = {
        f"{base}/pulls/1.patch": b"Subject: safe\n+one\n",
        f"{base}/pulls/2.patch": b"Authorization: Bearer SECRET\n+two\n",
    }
    client = StaticClient(pages, patches)

    pulls = collect_pull_requests(client, "zbnerd/probabilistic-valuation-engine", "snap", tmp_path)
    issues = collect_issues(client, "zbnerd/probabilistic-valuation-engine", "snap", tmp_path)

    assert pulls.numbers == (1, 2)
    assert issues.numbers == (3, 4)
    assert all(call[1] != f"{base}/search/issues" for call in client.calls)
    assert any(fingerprint.page_numbers == (1, 2) for fingerprint in pulls.endpoint_fingerprints)
    assert {record.source_type for record in pulls.records} >= {
        "github-pull-request", "github-pr-commit", "github-pr-file", "github-review",
        "github-review-comment", "github-conversation-comment", "github-timeline-event",
        "github-reaction", "github-requested-reviewer", "github-patch",
    }
    patch = next(record for record in pulls.records if record.source_type == "github-patch" and "pull/2" in record.source_locator)
    assert patch.raw_hash == hashlib.sha256(patches[f"{base}/pulls/2.patch"]).hexdigest()
    assert patch.stored_hash != patch.raw_hash
    assert patch.stored_members
    for record in pulls.records + issues.records:
        assert record.raw_hash
        assert record.stored_hash
        assert record.stored_members
    archives = sorted(tmp_path.glob("github-records-*.tar.gz"))
    assert archives
    with tarfile.open(archives[0], "r:gz") as archive:
        stored = b"".join(
            archive.extractfile(member).read()
            for member in archive.getmembers()
            if member.isfile()
        )
    assert b"SECRET" not in stored


@dataclass
class ScenarioClient:
    enumeration: int = 0
    review_version: int = 1

    def __post_init__(self):
        self.calls = []
        self._issue_list_calls = 0
        self._review_calls = 0

    def get_pages(self, path, params=None, accept="application/vnd.github+json"):
        self.calls.append((path, dict(params or {}), accept))
        base = "/repos/zbnerd/probabilistic-valuation-engine"
        if path == f"{base}/pulls":
            self.enumeration += 1
            updated = "u2-new" if self.enumeration >= 2 else "u2"
            return (_page(path, [{"number": 1, "updated_at": "u1"}, {"number": 2, "updated_at": updated}]),)
        if path == f"{base}/issues":
            self._issue_list_calls += 1
            rows = [{"number": 3, "updated_at": "u3"}, {"number": 4, "updated_at": "u4"}]
            if self._issue_list_calls >= 2:
                rows.append({"number": 5, "updated_at": "u5"})
            return (_page(path, rows),)
        if path == f"{base}/pulls/2/reviews":
            self._review_calls += 1
            # Mutates after the parent updated_at has stabilized.
            if self._review_calls >= 3:
                self.review_version = 2
        number = int(path.split("/")[5]) if len(path.split("/")) > 5 else 0
        mapping = (
            _pr_pages(
                number,
                updated_at="u2-new" if number == 2 else "u1",
                review_version=self.review_version,
            )
            if "/pulls/" in path or number in (1, 2)
            else _issue_pages(number)
        )
        return mapping.get(path, ())

    def get_bytes(self, path, params=None, accept="application/vnd.github+json"):
        self.calls.append((path, dict(params or {}), accept))
        return f"patch:{path}".encode()


def test_reconciliation_rehydrates_parent_and_child_deltas_then_requires_zero_delta(tmp_path):
    client = ScenarioClient()
    ticks = iter(f"2026-01-01T00:00:{value:02d}Z" for value in range(60))

    result = reconcile_github(
        client=client,
        repository="zbnerd/probabilistic-valuation-engine",
        snapshot_id="snap",
        archive_dir=tmp_path,
        now=lambda: next(ticks),
        max_passes=8,
    )

    assert result.window.pull_request_numbers == (1, 2)
    assert result.window.issue_numbers == (3, 4, 5)
    assert result.passes[-1].changed_items == ()
    assert any("pull:2" in item for item in result.passes[1].changed_items)
    assert any("issue:5" in item for item in result.passes[1].changed_items)
    assert any(pass_.changed_endpoint_keys for pass_ in result.passes[2:])
    assert client._review_calls >= 4
    assert result.window.enumeration_started_at < result.window.enumeration_completed_at < result.window.reconciled_at
    assert all(record.payload.get("fetched_at") for record in result.records)


def test_transient_or_incomplete_results_cannot_finalize(tmp_path):
    base = "/repos/zbnerd/probabilistic-valuation-engine"
    client = StaticClient({f"{base}/pulls": GitHubClientError("transient")})

    with pytest.raises(GitHubClientError, match="transient"):
        collect_pull_requests(client, "zbnerd/probabilistic-valuation-engine", "snap", tmp_path)

    broken = StaticClient({
        f"{base}/pulls": (_page(f"{base}/pulls", [{"number": 1, "updated_at": "u"}], 2),),
    })
    with pytest.raises(GitHubClientError, match="pagination"):
        collect_pull_requests(broken, "zbnerd/probabilistic-valuation-engine", "snap", tmp_path)


def test_final_unavailable_child_is_a_separate_availability_record(tmp_path):
    base = "/repos/zbnerd/probabilistic-valuation-engine"
    pages = {
        f"{base}/issues": (_page(f"{base}/issues", [{"number": 9, "updated_at": "u"}]),),
        **_issue_pages(9),
    }
    pages[f"{base}/issues/9/timeline"] = ()
    result = collect_issues(StaticClient(pages), "zbnerd/probabilistic-valuation-engine", "snap", tmp_path)

    unavailable = [record for record in result.records if record.source_type == "github-availability"]
    assert len(unavailable) == 1
    assert unavailable[0].availability_status == "confirmed-unavailable"
    fingerprint = next(value for value in result.endpoint_fingerprints if value.endpoint_key.endswith("timeline"))
    assert fingerprint.availability_status == "confirmed-unavailable"
