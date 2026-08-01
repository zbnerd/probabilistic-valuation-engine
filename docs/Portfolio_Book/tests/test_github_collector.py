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


def _page(
    path,
    value,
    page=1,
    *,
    status="available",
    status_code=200,
    fetched_at="2026-01-01T00:00:00Z",
):
    body = _body(value)
    return GitHubPage(
        endpoint=path,
        params={"page": page} if page > 1 else {},
        page_number=page,
        body=body,
        json=value,
        response_hash=hashlib.sha256(body).hexdigest(),
        availability_status=status,
        status_code=status_code,
        fetched_at=fetched_at,
    )


def _unavailable(path, status_code=404, body=b'{"message":"unsafe detail"}'):
    return GitHubPage(
        endpoint=path,
        params={},
        page_number=1,
        body=body,
        json=None,
        response_hash=hashlib.sha256(body).hexdigest(),
        availability_status="confirmed-unavailable",
        status_code=status_code,
        fetched_at="2026-01-02T03:04:05Z",
    )


class StaticClient:
    def __init__(self, pages, bytes_by_path=None):
        self.pages = pages
        self.bytes_by_path = bytes_by_path or {}
        self.calls = []

    def get_pages(self, path, params=None, accept="application/vnd.github+json"):
        self.calls.append(("pages", path, dict(params or {}), accept))
        value = self.pages.get(path, ())
        if callable(value):
            value = value(dict(params or {}))
        if isinstance(value, Exception):
            raise value
        return tuple(value)

    def get_bytes(self, path, params=None, accept="application/vnd.github+json"):
        self.calls.append(("bytes", path, dict(params or {}), accept))
        return self.bytes_by_path.get(path)

    def get_bytes_page(self, path, params=None, accept="application/vnd.github+json"):
        self.calls.append(("bytes-page", path, dict(params or {}), accept))
        value = self.bytes_by_path.get(path)
        if isinstance(value, GitHubPage):
            return value
        body = value
        if body is None:
            return _unavailable(path)
        return GitHubPage(
            endpoint=path,
            params=dict(params or {}),
            page_number=1,
            body=body,
            json=None,
            response_hash=hashlib.sha256(body).hexdigest(),
            availability_status="available",
            status_code=200,
            fetched_at="2026-01-01T00:00:00Z",
        )


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
    endpoint_predicates = {
        "github-pull-request": lambda path: "/pulls/" in path and path.rsplit("/", 1)[-1].isdigit(),
        "github-issue": lambda path: "/issues/" in path and path.rsplit("/", 1)[-1].isdigit(),
        "github-pr-commit": lambda path: path.endswith("/commits"),
        "github-pr-file": lambda path: path.endswith("/files"),
        "github-review": lambda path: path.endswith("/reviews"),
        "github-review-comment": lambda path: "/pulls/" in path and path.endswith("/comments"),
        "github-conversation-comment": lambda path: "/issues/" in path and path.endswith("/comments"),
        "github-timeline-event": lambda path: path.endswith("/timeline"),
        "github-reaction": lambda path: path.endswith("/reactions"),
        "github-requested-reviewer": lambda path: path.endswith("/requested_reviewers"),
    }
    for source_type, predicate in endpoint_predicates.items():
        typed = [record for record in pulls.records + issues.records if record.source_type == source_type]
        expected_hashes = {
            page.response_hash
            for path, value in pages.items()
            if predicate(path) and isinstance(value, tuple)
            for page in value
        }
        assert typed, source_type
        assert {record.raw_hash for record in typed} == expected_hashes, source_type
        assert all(record.payload["endpoint_response_raw_sha256"] == record.raw_hash for record in typed)
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


def test_github_json_redaction_preserves_nested_structure_and_control_escapes(tmp_path):
    base = "/repos/zbnerd/probabilistic-valuation-engine"
    pages = {
        f"{base}/issues": (_page(f"{base}/issues", [{"number": 9, "updated_at": "u"}]),),
        **_issue_pages(9),
    }
    detail = dict(pages[f"{base}/issues/9"][0].json)
    detail["evidence"] = {
        "control_sensitive": 'context token: "abc\\ndef" after',
        "contact": "reviewer@example.org",
        "token": "short-secret",
        "already_safe": "[REDACTED:third-party-email]",
        "values": [None, True, 17, 2.5],
    }
    detail_page = _page(f"{base}/issues/9", detail)
    pages[f"{base}/issues/9"] = (detail_page,)

    result = collect_issues(
        StaticClient(pages),
        "zbnerd/probabilistic-valuation-engine",
        "snap",
        tmp_path,
    )

    record = next(value for value in result.records if value.source_type == "github-issue")
    safe = record.payload["value"]
    evidence = safe["evidence"]
    assert tuple(evidence) == (
        "control_sensitive",
        "contact",
        "token",
        "already_safe",
        "values",
    )
    assert evidence["control_sensitive"] == "context token: [REDACTED:credential-value]"
    assert evidence["contact"] == "[REDACTED:third-party-email]"
    assert evidence["token"] == "[REDACTED:credential-value]"
    assert evidence["already_safe"] == "[REDACTED:third-party-email]"
    assert evidence["values"] == [None, True, 17, 2.5]
    assert set(record.privacy_redactions) >= {
        "credential-value",
        "third-party-email",
    }
    assert record.raw_hash == detail_page.response_hash
    canonical_safe = (
        json.dumps(safe, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        + "\n"
    ).encode()
    assert record.stored_hash == hashlib.sha256(canonical_safe).hexdigest()


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

    def get_bytes_page(self, path, params=None, accept="application/vnd.github+json"):
        body = self.get_bytes(path, params, accept)
        return GitHubPage(
            endpoint=path,
            params=dict(params or {}),
            page_number=1,
            body=body,
            json=None,
            response_hash=hashlib.sha256(body).hexdigest(),
            availability_status="available",
            status_code=200,
            fetched_at="2026-01-01T00:00:00Z",
        )


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
    unavailable_page = _unavailable(f"{base}/issues/9/timeline", 451)
    pages[f"{base}/issues/9/timeline"] = (unavailable_page,)
    result = collect_issues(StaticClient(pages), "zbnerd/probabilistic-valuation-engine", "snap", tmp_path)

    unavailable = [record for record in result.records if record.source_type == "github-availability"]
    assert len(unavailable) == 1
    assert unavailable[0].availability_status == "confirmed-unavailable"
    assert unavailable[0].raw_hash == unavailable_page.response_hash
    assert unavailable[0].payload["status_code"] == 451
    assert unavailable[0].payload["confirmed_at"] == unavailable_page.fetched_at
    assert unavailable[0].payload["request_params"] == {"per_page": 100}
    assert unavailable[0].payload["accept"] == "application/vnd.github+json"
    fingerprint = next(value for value in result.endpoint_fingerprints if value.endpoint_key.endswith("timeline"))
    assert fingerprint.availability_status == "confirmed-unavailable"
    assert fingerprint.page_response_hashes == (unavailable_page.response_hash,)
    assert "status-code:451" in fingerprint.stable_child_ids
    with tarfile.open(result.archive_paths[0], "r:gz") as archive:
        assert unavailable_page.body not in b"".join(
            archive.extractfile(member).read()
            for member in archive.getmembers()
            if member.isfile()
        )


@pytest.mark.parametrize(
    ("field", "bad_value"),
    [
        ("commits", None),
        ("changed_files", "1"),
        ("review_comments", True),
        ("comments", -1),
    ],
)
def test_available_pr_requires_valid_nonnegative_detail_counts(tmp_path, field, bad_value):
    base = "/repos/zbnerd/probabilistic-valuation-engine"
    pages = {
        f"{base}/pulls": (_page(f"{base}/pulls", [{"number": 1, "updated_at": "u"}]),),
        **_pr_pages(1),
    }
    detail = dict(pages[f"{base}/pulls/1"][0].json)
    if bad_value is None:
        detail.pop(field)
    else:
        detail[field] = bad_value
    pages[f"{base}/pulls/1"] = (_page(f"{base}/pulls/1", detail),)

    with pytest.raises(GitHubClientError, match=f"invalid detail count: {field}"):
        collect_pull_requests(StaticClient(pages, {f"{base}/pulls/1.patch": b"patch"}), "zbnerd/probabilistic-valuation-engine", "snap", tmp_path)


def test_parent_count_gap_is_one_record_only_fact_without_guessed_children(tmp_path):
    base = "/repos/zbnerd/probabilistic-valuation-engine"
    pages = {
        f"{base}/pulls": (_page(f"{base}/pulls", [{"number": 7, "updated_at": "u"}]),),
        **_pr_pages(7),
    }
    detail = {**pages[f"{base}/pulls/7"][0].json, "review_comments": 6}
    detail_page = _page(f"{base}/pulls/7", detail, fetched_at="2026-01-02T03:04:05Z")
    child_page = _page(
        f"{base}/pulls/7/comments",
        [],
        fetched_at="2026-01-02T03:04:06Z",
    )
    pages[f"{base}/pulls/7"] = (detail_page,)
    pages[f"{base}/pulls/7/comments"] = (child_page,)

    result = collect_pull_requests(
        StaticClient(pages, {f"{base}/pulls/7.patch": b"patch"}),
        "zbnerd/probabilistic-valuation-engine",
        "snap",
        tmp_path,
    )

    gaps = [record for record in result.records if record.source_type == "github-count-gap"]
    assert len(gaps) == 1
    gap = gaps[0]
    assert gap.classification == "record-only"
    assert gap.availability_status == "confirmed-unavailable"
    assert gap.record_only_reason == "parent-reported count exceeds accessible endpoint enumeration"
    assert gap.payload["item_key"] == "pull:7"
    assert gap.payload["endpoint_kind"] == "review-comments"
    assert gap.payload["expected_count"] == 6
    assert gap.payload["observed_count"] == 0
    assert gap.payload["missing_count"] == 6
    assert gap.payload["parent_detail_response_sha256"] == detail_page.response_hash
    assert gap.payload["parent_updated_at"] == detail["updated_at"]
    assert gap.payload["child_page_numbers"] == [1]
    assert gap.payload["child_page_response_sha256"] == [child_page.response_hash]
    assert gap.payload["child_stable_ids"] == []
    assert gap.payload["child_page_fetched_at"] == [child_page.fetched_at]
    assert "deleted" not in json.dumps(gap.payload).lower()
    assert "content" not in gap.payload
    fingerprint = next(
        value
        for value in result.endpoint_fingerprints
        if value.endpoint_key == f"{base}/pulls/7/comments"
    )
    gap_tokens = [value for value in fingerprint.stable_child_ids if value.startswith("count-gap:")]
    assert len(gap_tokens) == 1
    assert gap.source_locator == f"github:{fingerprint.endpoint_key}#{gap_tokens[0]}"
    assert len([record for record in result.records if record.source_type == "github-review-comment"]) == 0


class ChangingCountGapClient:
    def __init__(self):
        self.detail_calls = 0

    def get_pages(self, path, params=None, accept="application/vnd.github+json"):
        base = "/repos/zbnerd/probabilistic-valuation-engine"
        if path == f"{base}/pulls":
            return (_page(path, [{"number": 7, "updated_at": "u"}]),)
        if path == f"{base}/issues":
            return (_page(path, []),)
        pages = _pr_pages(7)
        if path == f"{base}/pulls/7":
            self.detail_calls += 1
            expected = 5 if self.detail_calls == 1 else 6
            detail = {**pages[path][0].json, "review_comments": expected}
            return (_page(path, detail),)
        if path == f"{base}/pulls/7/comments":
            return (_page(path, []),)
        return pages[path]

    def get_bytes_page(self, path, params=None, accept="application/vnd.github+json"):
        body = b"patch"
        return GitHubPage(
            endpoint=path,
            params=dict(params or {}),
            page_number=1,
            body=body,
            json=None,
            response_hash=hashlib.sha256(body).hexdigest(),
            availability_status="available",
            status_code=200,
            fetched_at="2026-01-01T00:00:00Z",
        )


def test_changed_count_gap_requires_then_reaches_a_following_zero_delta_pass(tmp_path):
    client = ChangingCountGapClient()
    ticks = iter(f"2026-01-01T00:00:{value:02d}Z" for value in range(30))

    result = reconcile_github(
        client=client,
        repository="zbnerd/probabilistic-valuation-engine",
        snapshot_id="snap",
        archive_dir=tmp_path,
        now=lambda: next(ticks),
        max_passes=4,
    )

    assert client.detail_calls == 3
    assert any("/pulls/7/comments" in key for key in result.passes[1].changed_endpoint_keys)
    assert result.passes[2].changed_items == ()
    assert result.passes[2].changed_endpoint_keys == ()
    gap = next(record for record in result.records if record.source_type == "github-count-gap")
    assert gap.payload["expected_count"] == 6
    assert gap.payload["missing_count"] == 6


def test_count_gap_requires_complete_available_pagination_proof(tmp_path):
    base = "/repos/zbnerd/probabilistic-valuation-engine"
    pages = {
        f"{base}/pulls": (_page(f"{base}/pulls", [{"number": 7, "updated_at": "u"}]),),
        **_pr_pages(7),
    }
    detail = {**pages[f"{base}/pulls/7"][0].json, "review_comments": 101}
    pages[f"{base}/pulls/7"] = (_page(f"{base}/pulls/7", detail),)
    endpoint = f"{base}/pulls/7/comments"

    def comments(params):
        if params.get("page", 1) == 1:
            return (_page(endpoint, [{"id": value} for value in range(100)]),)
        unavailable = _unavailable(endpoint)
        return (
            GitHubPage(
                endpoint=endpoint,
                params={"page": 2, "per_page": 100},
                page_number=2,
                body=unavailable.body,
                json=None,
                response_hash=unavailable.response_hash,
                availability_status=unavailable.availability_status,
                status_code=unavailable.status_code,
                fetched_at=unavailable.fetched_at,
            ),
        )

    pages[endpoint] = comments
    with pytest.raises(GitHubClientError, match="availability pagination"):
        collect_pull_requests(
            StaticClient(pages, {f"{base}/pulls/7.patch": b"patch"}),
            "zbnerd/probabilistic-valuation-engine",
            "snap",
            tmp_path,
        )


def test_parent_count_lower_than_observed_children_remains_blocking(tmp_path):
    base = "/repos/zbnerd/probabilistic-valuation-engine"
    pages = {
        f"{base}/pulls": (_page(f"{base}/pulls", [{"number": 7, "updated_at": "u"}]),),
        **_pr_pages(7),
    }
    detail = {**pages[f"{base}/pulls/7"][0].json, "review_comments": 0}
    pages[f"{base}/pulls/7"] = (_page(f"{base}/pulls/7", detail),)

    with pytest.raises(GitHubClientError, match="expected=0 actual=2"):
        collect_pull_requests(
            StaticClient(pages, {f"{base}/pulls/7.patch": b"patch"}),
            "zbnerd/probabilistic-valuation-engine",
            "snap",
            tmp_path,
        )


def test_full_enumeration_page_uses_nonempty_then_empty_sentinel_pages(tmp_path):
    base = "/repos/zbnerd/probabilistic-valuation-engine"
    endpoint = f"{base}/issues"
    first = [
        {"number": number, "updated_at": "u", "pull_request": {}}
        for number in range(1, 101)
    ]

    def enumeration(params):
        page = params.get("page", 1)
        if page == 1:
            return (_page(endpoint, first),)
        if page == 2:
            second = [
                {"number": number, "updated_at": "u", "pull_request": {}}
                for number in range(101, 201)
            ]
            return (_page(endpoint, second, 2),)
        return (_page(endpoint, [], 3),)

    client = StaticClient({endpoint: enumeration})
    result = collect_issues(client, "zbnerd/probabilistic-valuation-engine", "snap", tmp_path)

    assert result.numbers == ()
    assert result.endpoint_fingerprints[0].page_numbers == (1, 2, 3)
    assert [call[2].get("page", 1) for call in client.calls] == [1, 2, 3]


def test_full_unbounded_child_page_requires_empty_sentinel_proof(tmp_path):
    base = "/repos/zbnerd/probabilistic-valuation-engine"
    pages = {
        f"{base}/issues": (_page(f"{base}/issues", [{"number": 9, "updated_at": "u"}]),),
        **_issue_pages(9),
    }
    endpoint = f"{base}/issues/9/reactions"

    def reactions(params):
        if params.get("page", 1) == 1:
            return (_page(endpoint, [{"id": number} for number in range(100)]),)
        return (_page(endpoint, [], 2),)

    pages[endpoint] = reactions
    client = StaticClient(pages)
    result = collect_issues(client, "zbnerd/probabilistic-valuation-engine", "snap", tmp_path)

    fingerprint = next(value for value in result.endpoint_fingerprints if value.endpoint_key == endpoint)
    assert fingerprint.page_numbers == (1, 2)
    assert any(call[2].get("page") == 2 for call in client.calls if call[1] == endpoint)


def test_full_page_without_available_sentinel_cannot_finalize(tmp_path):
    base = "/repos/zbnerd/probabilistic-valuation-engine"
    endpoint = f"{base}/issues"
    full_page = [
        {"number": number, "updated_at": "u", "pull_request": {}}
        for number in range(1, 101)
    ]

    def capped(params):
        if params.get("page", 1) == 1:
            return (_page(endpoint, full_page),)
        unavailable = _unavailable(endpoint)
        return (
            GitHubPage(
                endpoint=unavailable.endpoint,
                params={"page": 2, "per_page": 100, "state": "all"},
                page_number=2,
                body=unavailable.body,
                json=None,
                response_hash=unavailable.response_hash,
                availability_status=unavailable.availability_status,
                status_code=unavailable.status_code,
                fetched_at=unavailable.fetched_at,
            ),
        )

    with pytest.raises(GitHubClientError, match="availability pagination"):
        collect_issues(StaticClient({endpoint: capped}), "zbnerd/probabilistic-valuation-engine", "snap", tmp_path)


class TimestampChangingUnavailableClient:
    def __init__(self):
        self.timeline_calls = 0

    def get_pages(self, path, params=None, accept="application/vnd.github+json"):
        base = "/repos/zbnerd/probabilistic-valuation-engine"
        if path == f"{base}/pulls":
            return (_page(path, []),)
        if path == f"{base}/issues":
            return (_page(path, [{"number": 9, "updated_at": "u"}]),)
        if path == f"{base}/issues/9":
            return (
                _page(
                    path,
                    {
                        "id": 2009,
                        "number": 9,
                        "title": "Issue 9",
                        "state": "closed",
                        "updated_at": "u",
                        "comments": 0,
                    },
                ),
            )
        if path in (f"{base}/issues/9/comments", f"{base}/issues/9/reactions"):
            return (_page(path, []),)
        if path == f"{base}/issues/9/timeline":
            self.timeline_calls += 1
            changed = self.timeline_calls >= 2
            body = b'{"message":"gone"}' if changed else b'{"message":"missing"}'
            return (
                GitHubPage(
                    endpoint=path,
                    params=dict(params or {}),
                    page_number=1,
                    body=body,
                    json=None,
                    response_hash=hashlib.sha256(body).hexdigest(),
                    availability_status="confirmed-unavailable",
                    status_code=410 if changed else 404,
                    fetched_at=f"2026-01-02T03:04:{self.timeline_calls:02d}Z",
                ),
            )
        raise AssertionError(path)


def test_unavailable_fingerprint_ignores_observation_time_but_detects_status_and_body(tmp_path):
    client = TimestampChangingUnavailableClient()
    ticks = iter(f"2026-01-01T00:00:{value:02d}Z" for value in range(20))

    result = reconcile_github(
        client=client,
        repository="zbnerd/probabilistic-valuation-engine",
        snapshot_id="snap",
        archive_dir=tmp_path,
        now=lambda: next(ticks),
        max_passes=4,
    )

    assert client.timeline_calls == 3
    assert any("timeline" in key for key in result.passes[1].changed_endpoint_keys)
    assert result.passes[2].changed_items == ()
    unavailable = next(record for record in result.records if record.source_type == "github-availability")
    assert unavailable.payload["status_code"] == 410
    assert unavailable.payload["confirmed_at"] == "2026-01-02T03:04:03Z"
    fingerprint = next(value for value in result.window.endpoint_fingerprints if value.endpoint_key.endswith("timeline"))
    assert "status-code:410" in fingerprint.stable_child_ids
    assert all(not identity.startswith("confirmed-at:") for identity in fingerprint.stable_child_ids)
