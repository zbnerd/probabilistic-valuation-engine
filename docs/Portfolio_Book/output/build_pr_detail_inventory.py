#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.12,<3.13"
# dependencies = [
#   "requests==2.31.0",
# ]
# ///
"""Fetch and validate the PR detail records missed by the original audit.

The first run discovers the 209 historical GraphQL-502 records in
``pr_inventory.md`` and also refreshes PR #1464. Later runs discover the same
scope from the generated JSONL companion. Public discussion bodies are hashed,
not reproduced, so the evidence remains verifiable without copying untrusted
free-form text into the portfolio artifacts.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import tempfile
import threading
import time
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import requests


REPOSITORY = "zbnerd/probabilistic-valuation-engine"
API_ROOT = "https://api.github.com"
GRAPHQL_URL = f"{API_ROOT}/graphql"
SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parents[2]
RESEARCH_DIR = SCRIPT_DIR / "research"
MARKDOWN_PATH = RESEARCH_DIR / "pr_inventory.md"
COMPANION_PATH = RESEARCH_DIR / "pr_detail_inventory.jsonl"
HISTORICAL_SCOPE = "historical_graphql_502_gap"
LIVE_ADDITION_SCOPE = "live_addition_full_detail"
EXPECTED_HISTORICAL_COUNT = 209
LIVE_ADDITION_NUMBER = 1464
MAX_WORKERS = 4

PR_BLOCK_RE = re.compile(r"^### PR #(\d+).*?(?=^### PR #|\Z)", re.MULTILINE | re.DOTALL)
FULL_SHA_RE = re.compile(r"^[0-9a-f]{40}$")

LINKED_ISSUES_QUERY = """
query($owner: String!, $name: String!, $number: Int!, $cursor: String) {
  repository(owner: $owner, name: $name) {
    pullRequest(number: $number) {
      reviews(first: 1) { totalCount }
      closingIssuesReferences(first: 100, after: $cursor) {
        totalCount
        nodes { number url state }
        pageInfo { hasNextPage endCursor }
      }
    }
  }
}
"""

COMMITS_QUERY = """
query($owner: String!, $name: String!, $number: Int!, $cursor: String) {
  repository(owner: $owner, name: $name) {
    pullRequest(number: $number) {
      commits(first: 100, after: $cursor) {
        totalCount
        nodes { commit { oid } }
        pageInfo { hasNextPage endCursor }
      }
    }
  }
}
"""

_thread_state = threading.local()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--validate-only",
        action="store_true",
        help="validate the existing JSONL and Markdown without network access",
    )
    parser.add_argument(
        "--workers",
        type=int,
        default=MAX_WORKERS,
        help=f"parallel PR fetches (default: {MAX_WORKERS})",
    )
    return parser.parse_args()


def read_token() -> str:
    result = subprocess.run(
        ["gh", "auth", "token"],
        check=True,
        capture_output=True,
        text=True,
    )
    token = result.stdout.strip()
    if not token:
        raise RuntimeError("gh returned an empty authentication token")
    return token


def session_for_thread(token: str) -> requests.Session:
    session = getattr(_thread_state, "session", None)
    if session is None:
        session = requests.Session()
        session.headers.update(
            {
                "Accept": "application/vnd.github+json",
                "Authorization": f"Bearer {token}",
                "X-GitHub-Api-Version": "2022-11-28",
                "User-Agent": "pve-portfolio-pr-evidence-audit",
            }
        )
        _thread_state.session = session
    return session


def request_json(
    session: requests.Session,
    method: str,
    url: str,
    *,
    params: dict[str, Any] | None = None,
    payload: dict[str, Any] | None = None,
) -> tuple[Any, requests.Response]:
    retryable = {429, 500, 502, 503, 504}
    for attempt in range(8):
        response = session.request(
            method,
            url,
            params=params,
            json=payload,
            timeout=(10, 45),
        )
        if response.status_code < 400:
            return response.json(), response

        remaining = response.headers.get("X-RateLimit-Remaining")
        if response.status_code == 403 and remaining == "0":
            reset = response.headers.get("X-RateLimit-Reset", "unknown")
            raise RuntimeError(f"GitHub rate limit exhausted; reset epoch={reset}")
        if response.status_code not in retryable or attempt == 7:
            excerpt = response.text[:400].replace("\n", " ")
            raise RuntimeError(
                f"GitHub API {method} {url} returned {response.status_code}: {excerpt}"
            )

        retry_after = response.headers.get("Retry-After")
        delay = min(float(retry_after) if retry_after else 2**attempt, 30.0)
        time.sleep(delay)

    raise AssertionError("unreachable retry loop")


def rest_get(
    session: requests.Session,
    path: str,
) -> dict[str, Any]:
    data, _ = request_json(session, "GET", f"{API_ROOT}/repos/{REPOSITORY}/{path}")
    if not isinstance(data, dict):
        raise TypeError(f"expected object from REST path {path}")
    return data


def rest_connection(
    session: requests.Session,
    path: str,
    extra_params: dict[str, Any] | None = None,
) -> list[dict[str, Any]]:
    url = f"{API_ROOT}/repos/{REPOSITORY}/{path}"
    params: dict[str, Any] | None = {"per_page": 100, **(extra_params or {})}
    nodes: list[dict[str, Any]] = []
    while url:
        data, response = request_json(session, "GET", url, params=params)
        if not isinstance(data, list):
            raise TypeError(f"expected list from paginated REST path {path}")
        nodes.extend(data)
        next_link = response.links.get("next")
        url = next_link["url"] if next_link else ""
        params = None
    return nodes


def graphql_linked_issues(
    session: requests.Session,
    number: int,
) -> tuple[list[dict[str, Any]], int]:
    owner, name = REPOSITORY.split("/", maxsplit=1)
    cursor: str | None = None
    issues: list[dict[str, Any]] = []
    expected_issues: int | None = None
    expected_reviews: int | None = None
    while True:
        payload = {
            "query": LINKED_ISSUES_QUERY,
            "variables": {
                "owner": owner,
                "name": name,
                "number": number,
                "cursor": cursor,
            },
        }
        data, _ = request_json(session, "POST", GRAPHQL_URL, payload=payload)
        if data.get("errors"):
            raise RuntimeError(f"GraphQL errors for PR #{number}: {data['errors']}")
        pull_request = data["data"]["repository"]["pullRequest"]
        if pull_request is None:
            raise RuntimeError(f"GraphQL did not return PR #{number}")
        connection = pull_request["closingIssuesReferences"]
        expected_issues = connection["totalCount"]
        expected_reviews = pull_request["reviews"]["totalCount"]
        issues.extend(connection["nodes"])
        page_info = connection["pageInfo"]
        if not page_info["hasNextPage"]:
            break
        cursor = page_info["endCursor"]
        if not cursor:
            raise RuntimeError(f"missing closing-issue cursor for PR #{number}")

    if expected_issues is None or expected_reviews is None:
        raise AssertionError("GraphQL totals were not populated")
    if len(issues) != expected_issues:
        raise RuntimeError(
            f"PR #{number}: closing issues {len(issues)} != GraphQL total {expected_issues}"
        )
    return issues, expected_reviews


def graphql_commit_shas(
    session: requests.Session,
    number: int,
) -> list[str]:
    """Read the full commit connection when REST reaches its 250-commit cap."""
    owner, name = REPOSITORY.split("/", maxsplit=1)
    cursor: str | None = None
    shas: list[str] = []
    expected: int | None = None
    while True:
        payload = {
            "query": COMMITS_QUERY,
            "variables": {
                "owner": owner,
                "name": name,
                "number": number,
                "cursor": cursor,
            },
        }
        data, _ = request_json(session, "POST", GRAPHQL_URL, payload=payload)
        if data.get("errors"):
            raise RuntimeError(f"GraphQL commit errors for PR #{number}: {data['errors']}")
        pull_request = data["data"]["repository"]["pullRequest"]
        if pull_request is None:
            raise RuntimeError(f"GraphQL did not return PR #{number}")
        connection = pull_request["commits"]
        expected = connection["totalCount"]
        shas.extend(node["commit"]["oid"] for node in connection["nodes"])
        page_info = connection["pageInfo"]
        if not page_info["hasNextPage"]:
            break
        cursor = page_info["endCursor"]
        if not cursor:
            raise RuntimeError(f"missing commit cursor for PR #{number}")

    if expected is None or len(shas) != expected:
        raise RuntimeError(
            f"PR #{number}: GraphQL commits {len(shas)} != total {expected}"
        )
    return shas


def rest_commit_shas_beyond_pr_cap(
    session: requests.Session,
    number: int,
    base_sha: str,
    head_sha: str,
    pr_prefix: list[str],
    expected: int,
) -> list[str]:
    """Recover the exact local base..head set beyond GitHub's 250-node cap."""
    del session  # The API prefix and total were already read by the caller.
    result = subprocess.run(
        ["git", "rev-list", "--reverse", head_sha, f"^{base_sha}"],
        cwd=REPO_ROOT,
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    commits = result.stdout.splitlines()
    assert_count(number, "local base..head range", len(commits), expected)
    if not set(pr_prefix).issubset(set(commits)):
        raise RuntimeError(f"PR #{number}: API commit window is not contained in local base..head range")
    return commits


def body_evidence(value: str | None) -> dict[str, Any]:
    encoded = (value or "").encode("utf-8")
    return {
        "bytes": len(encoded),
        "sha256": hashlib.sha256(encoded).hexdigest() if encoded else None,
    }


def login_of(node: dict[str, Any]) -> str | None:
    user = node.get("user")
    return user.get("login") if isinstance(user, dict) else None


def compact_review(node: dict[str, Any]) -> dict[str, Any]:
    return {
        "author_login": login_of(node),
        "body_evidence": body_evidence(node.get("body")),
        "commit_id": node.get("commit_id"),
        "html_url": node.get("html_url"),
        "id": node["id"],
        "state": node.get("state"),
        "submitted_at": node.get("submitted_at"),
    }


def compact_review_comment(node: dict[str, Any]) -> dict[str, Any]:
    return {
        "author_login": login_of(node),
        "body_evidence": body_evidence(node.get("body")),
        "commit_id": node.get("commit_id"),
        "created_at": node.get("created_at"),
        "html_url": node.get("html_url"),
        "id": node["id"],
        "in_reply_to_id": node.get("in_reply_to_id"),
        "line": node.get("line"),
        "original_commit_id": node.get("original_commit_id"),
        "original_line": node.get("original_line"),
        "path": node.get("path"),
        "pull_request_review_id": node.get("pull_request_review_id"),
        "side": node.get("side"),
        "start_line": node.get("start_line"),
        "start_side": node.get("start_side"),
        "updated_at": node.get("updated_at"),
    }


def compact_conversation_comment(node: dict[str, Any]) -> dict[str, Any]:
    return {
        "author_association": node.get("author_association"),
        "author_login": login_of(node),
        "body_evidence": body_evidence(node.get("body")),
        "created_at": node.get("created_at"),
        "html_url": node.get("html_url"),
        "id": node["id"],
        "updated_at": node.get("updated_at"),
    }


def compact_file(node: dict[str, Any]) -> dict[str, Any]:
    result = {
        "additions": node["additions"],
        "changes": node["changes"],
        "deletions": node["deletions"],
        "filename": node["filename"],
        "status": node["status"].upper(),
    }
    if node.get("previous_filename"):
        result["previous_filename"] = node["previous_filename"]
    return result


def assert_count(number: int, label: str, actual: int, expected: int) -> None:
    if actual != expected:
        raise RuntimeError(f"PR #{number}: {label} {actual} != API total {expected}")


def fetch_record(number: int, scope: str, token: str) -> dict[str, Any]:
    session = session_for_thread(token)
    metadata = rest_get(session, f"pulls/{number}")
    reviews_raw = rest_connection(session, f"pulls/{number}/reviews")
    review_comments_raw = rest_connection(session, f"pulls/{number}/comments")
    conversation_comments_raw = rest_connection(session, f"issues/{number}/comments")
    commits_raw = rest_connection(session, f"pulls/{number}/commits")
    files_raw = rest_connection(session, f"pulls/{number}/files")
    linked_issues_raw, graphql_review_count = graphql_linked_issues(session, number)

    assert_count(number, "reviews", len(reviews_raw), graphql_review_count)
    assert_count(number, "review comments", len(review_comments_raw), metadata["review_comments"])
    assert_count(number, "conversation comments", len(conversation_comments_raw), metadata["comments"])
    assert_count(number, "files", len(files_raw), metadata["changed_files"])

    commits = [node["sha"] for node in commits_raw]
    commit_source = "github_rest_paginated"
    if len(commits) != metadata["commits"]:
        # GitHub documents a hard 250-commit ceiling on the PR connection and
        # recommends List commits with the branch SHA for larger pull requests.
        if len(commits) != 250 or metadata["commits"] <= 250:
            assert_count(number, "commits", len(commits), metadata["commits"])
        commits = rest_commit_shas_beyond_pr_cap(
            session,
            number,
            metadata["base"]["sha"],
            metadata["head"]["sha"],
            commits,
            metadata["commits"],
        )
        commit_source = "local_git_base_head_after_github_250_cap"
    assert_count(number, "commits", len(commits), metadata["commits"])
    if len(commits) != len(set(commits)):
        raise RuntimeError(f"PR #{number}: duplicate connected commit SHA")
    if not all(FULL_SHA_RE.fullmatch(sha) for sha in commits):
        raise RuntimeError(f"PR #{number}: non-full connected commit SHA")

    reviews = sorted(
        (compact_review(node) for node in reviews_raw),
        key=lambda node: (node["submitted_at"] or "", node["id"]),
    )
    review_comments = sorted(
        (compact_review_comment(node) for node in review_comments_raw),
        key=lambda node: (node["created_at"] or "", node["id"]),
    )
    conversation_comments = sorted(
        (compact_conversation_comment(node) for node in conversation_comments_raw),
        key=lambda node: (node["created_at"] or "", node["id"]),
    )
    linked_issues = sorted(linked_issues_raw, key=lambda node: node["number"])
    files = sorted(
        (compact_file(node) for node in files_raw),
        key=lambda node: (node["filename"], node.get("previous_filename", "")),
    )

    return {
        "author_login": login_of(metadata),
        "base_sha": metadata["base"]["sha"],
        "body_evidence": body_evidence(metadata.get("body")),
        "capture_scope": scope,
        "closed_at": metadata.get("closed_at"),
        "commits": commits,
        "commit_source": commit_source,
        "conversation_comments": conversation_comments,
        "counts": {
            "closing_issues": len(linked_issues),
            "commits": len(commits),
            "conversation_comments": len(conversation_comments),
            "files": len(files),
            "review_comments": len(review_comments),
            "reviews": len(reviews),
        },
        "created_at": metadata["created_at"],
        "files": files,
        "head_sha": metadata["head"]["sha"],
        "html_url": metadata["html_url"],
        "linked_issues": linked_issues,
        "merge_commit_sha": metadata.get("merge_commit_sha"),
        "merged_at": metadata.get("merged_at"),
        "number": number,
        "review_comments": review_comments,
        "reviews": reviews,
        "schema_version": 1,
        "state": metadata["state"],
        "title": metadata["title"],
        "updated_at": metadata["updated_at"],
    }


def discover_scopes(markdown: str) -> dict[int, str]:
    if COMPANION_PATH.exists():
        existing = read_companion()
        scopes = {record["number"]: record["capture_scope"] for record in existing}
    else:
        scopes = {}
        for match in PR_BLOCK_RE.finditer(markdown):
            if "inaccessible for this record" in match.group(0):
                scopes[int(match.group(1))] = HISTORICAL_SCOPE
        scopes[LIVE_ADDITION_NUMBER] = LIVE_ADDITION_SCOPE
    validate_scopes(scopes)
    return scopes


def validate_scopes(scopes: dict[int, str]) -> None:
    historical = [number for number, scope in scopes.items() if scope == HISTORICAL_SCOPE]
    additions = [number for number, scope in scopes.items() if scope == LIVE_ADDITION_SCOPE]
    if len(historical) != EXPECTED_HISTORICAL_COUNT:
        raise RuntimeError(
            f"expected {EXPECTED_HISTORICAL_COUNT} historical gap PRs, found {len(historical)}"
        )
    if additions != [LIVE_ADDITION_NUMBER]:
        raise RuntimeError(f"expected only PR #{LIVE_ADDITION_NUMBER} as live addition")


def atomic_write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        mode="w",
        encoding="utf-8",
        newline="\n",
        dir=path.parent,
        delete=False,
    ) as handle:
        handle.write(content)
        temporary = Path(handle.name)
    os.replace(temporary, path)


def write_companion(records: list[dict[str, Any]]) -> None:
    lines = [
        json.dumps(record, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        for record in sorted(records, key=lambda record: record["number"])
    ]
    atomic_write(COMPANION_PATH, "\n".join(lines) + "\n")


def read_companion() -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for line_number, line in enumerate(COMPANION_PATH.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            raise RuntimeError(f"blank line in companion at line {line_number}")
        record = json.loads(line)
        records.append(record)
    return records


def markdown_path(value: str) -> str:
    return value.replace("`", "\\`")


def render_detail(record: dict[str, Any], portfolio_category: str) -> list[str]:
    counts = record["counts"]
    states = Counter(node["state"] for node in record["reviews"])
    state_summary = ", ".join(f"{state}={count}" for state, count in sorted(states.items()))
    state_summary = state_summary or "none"
    companion_link = "[machine-readable companion](./pr_detail_inventory.jsonl)"

    commits = record["commits"]
    commit_sample = ", ".join(sha[:7] for sha in commits[:8])
    if len(commits) > 8:
        commit_sample += f", … +{len(commits) - 8}"
    commit_sample = commit_sample or "none"

    linked_numbers = [f"#{node['number']}" for node in record["linked_issues"]]
    linked_summary = ", ".join(linked_numbers) or "none"

    status_counts = Counter(node["status"] for node in record["files"])
    status_summary = ", ".join(
        f"{status}={count}" for status, count in sorted(status_counts.items())
    )
    additions = sum(node["additions"] for node in record["files"])
    deletions = sum(node["deletions"] for node in record["files"])
    file_sample_parts = []
    for node in record["files"][:3]:
        file_sample_parts.append(
            f"{node['status']} `{markdown_path(node['filename'])}` "
            f"+{node['additions']}/-{node['deletions']}"
        )
    file_sample = "; ".join(file_sample_parts) or "none"

    merged = record["merged_at"] is not None
    resolution = (
        "merged by GitHub metadata; paginated file/commit/discussion evidence above"
        if merged
        else "closed without merge; evidence is not treated as applied"
    )
    return [
        (
            f"- reviews/discussion: {counts['reviews']} reviews "
            f"[{state_summary}]; {counts['review_comments']} review comments; "
            f"{counts['conversation_comments']} conversation comments. "
            f"Complete IDs, timestamps, and body hashes: {companion_link}, PR #{record['number']}."
        ),
        (
            f"- commits: {counts['commits']} [{commit_sample}]; linked issues: "
            f"{counts['closing_issues']} [{linked_summary}]. All 40-character connected SHAs "
            f"and formal `closingIssuesReferences`: {companion_link}."
        ),
        (
            f"- file evidence: {counts['files']} [{status_summary or 'none'}; "
            f"+{additions}/-{deletions}]. Sample: {file_sample}. "
            f"Complete paginated paths/counts: {companion_link}."
        ),
        f"- Actual: {resolution}. Portfolio: {portfolio_category}.",
    ]


def rewrite_record_blocks(markdown: str, records: list[dict[str, Any]]) -> str:
    by_number = {record["number"]: record for record in records}
    pieces: list[str] = []
    cursor = 0
    for match in PR_BLOCK_RE.finditer(markdown):
        pieces.append(markdown[cursor : match.start()])
        block = match.group(0)
        number = int(match.group(1))
        record = by_number.get(number)
        if record is None:
            pieces.append(block)
            cursor = match.end()
            continue

        category_match = re.search(r"Portfolio: ([^\.\n]+)\.", block)
        if category_match is None:
            raise RuntimeError(f"PR #{number}: portfolio category missing from Markdown")
        lines = block.rstrip().splitlines()
        detail_index = next(
            (
                index
                for index, line in enumerate(lines)
                if line.lower().startswith("- reviews/discussion")
            ),
            None,
        )
        if detail_index is None:
            raise RuntimeError(f"PR #{number}: detail section missing from Markdown")
        prefix = lines[:detail_index]
        replacement = prefix + render_detail(record, category_match.group(1))
        pieces.append("\n".join(replacement) + "\n\n")
        cursor = match.end()
    pieces.append(markdown[cursor:])
    return "".join(pieces).rstrip() + "\n"


def rewrite_header(markdown: str, captured_at: str) -> str:
    records_index = markdown.index("## Records")
    records = markdown[records_index:]
    header = f"""# Pull Request Inventory — GitHub Evidence Audit

## Scope and method

- Repository: `{REPOSITORY}`
- Original retrieval (UTC): 2026-08-01T05:40:48Z; this preserved the original 709 records below.
- Live reconciliation (UTC): 2026-08-08T14:16:30Z. Authenticated GitHub REST `GET /repos/{REPOSITORY}/pulls?state=all&per_page=100` was read with `--paginate`, independently checked against GitHub Search `is:pr` and GraphQL `repository.pullRequests.totalCount`.
- Live counts: REST enumeration 710 unique PR numbers; Search `total_count` 710 (`incomplete_results: false`); GraphQL total 710; duplicate count 0. REST state distribution: 710 closed, of which 699 have `merged_at` and 11 are closed without `merged_at`; 0 open.
- Original detail source: authenticated GitHub GraphQL `repository.pullRequests`, ordered by `CREATED_AT` ascending, for the 500 records that completed in the original aggregate retrieval.
- Detail-gap refresh (UTC): {captured_at}. The 209 records previously blocked by aggregate GraphQL HTTP 502, plus live-added PR #1464, were read independently. Each PR used paginated REST connections for reviews, review comments, conversation comments, commits, and changed files; formal linked issues used paginated GraphQL `closingIssuesReferences`. GitHub caps PR #1196's 288-commit relation at 250 nodes, so its full set is the locally reachable `head ^base` range: 288 unique objects matching API metadata, with all 250 API-returned SHAs contained in the set.
- Deterministic companion: [`pr_detail_inventory.jsonl`](./pr_detail_inventory.jsonl) contains 210 records sorted by PR number. It preserves complete connected commit SHAs (including all 50 for PR #1464), all returned file paths, discussion IDs/timestamps, formal linked issues, and hashes/byte counts rather than bodies for untrusted free-form discussion text.
- Privacy: email-like strings in Markdown summaries remain redacted; credential material and raw discussion bodies are not reproduced.

## Interpretation safeguards

- `MERGED`/a non-null `mergedAt` is the sole basis for calling a PR merged. `CLOSED` without that evidence is explicitly treated as not applied.
- The change/resolution statement is based on returned changed-file metadata (path, type, additions, deletions) plus commit IDs and PR body summary; it is not a claim that unmerged work was deployed.
- “Linked issues” means GitHub `closingIssuesReferences`; textual mentions may be present but are not asserted as formal links.
- “Discussion” separates review submissions, inline review comments, and conversation comments. The refreshed records were followed through every API page, and their returned counts were checked against GitHub totals.

## Detail completeness

The earlier 209-record HTTP-502 limitation is closed by the per-PR refresh above. For every refreshed record, connected commit and changed-file counts match PR REST metadata; review counts match GraphQL totals; review-comment and conversation-comment counts match PR REST metadata; and formal closing-issue counts match GraphQL totals. The JSONL companion is the complete machine-readable evidence surface for those connections, while this Markdown remains compact.

"""
    return header + records


def validate_record(record: dict[str, Any]) -> None:
    number = record["number"]
    counts = record["counts"]
    expected_lengths = {
        "closing_issues": len(record["linked_issues"]),
        "commits": len(record["commits"]),
        "conversation_comments": len(record["conversation_comments"]),
        "files": len(record["files"]),
        "review_comments": len(record["review_comments"]),
        "reviews": len(record["reviews"]),
    }
    if counts != expected_lengths:
        raise RuntimeError(f"PR #{number}: stored counts do not match arrays")
    if not all(FULL_SHA_RE.fullmatch(sha) for sha in record["commits"]):
        raise RuntimeError(f"PR #{number}: companion contains a non-full commit SHA")
    if len(record["commits"]) != len(set(record["commits"])):
        raise RuntimeError(f"PR #{number}: companion contains duplicate commit SHAs")
    if record.get("commit_source") not in {
        "github_rest_paginated",
        "local_git_base_head_after_github_250_cap",
    }:
        raise RuntimeError(f"PR #{number}: invalid commit source")
    if record["commit_source"] == "local_git_base_head_after_github_250_cap":
        result = subprocess.run(
            ["git", "rev-list", "--reverse", record["head_sha"], f"^{record['base_sha']}"],
            cwd=REPO_ROOT,
            check=True,
            capture_output=True,
            text=True,
            encoding="utf-8",
        )
        if record["commits"] != result.stdout.splitlines():
            raise RuntimeError(f"PR #{number}: stored commits differ from local base..head range")
    if record["files"] != sorted(
        record["files"], key=lambda node: (node["filename"], node.get("previous_filename", ""))
    ):
        raise RuntimeError(f"PR #{number}: companion files are not deterministic")


def validate_outputs() -> dict[str, Any]:
    records = read_companion()
    numbers = [record["number"] for record in records]
    if numbers != sorted(numbers) or len(numbers) != len(set(numbers)):
        raise RuntimeError("companion PR numbers are not sorted and unique")
    scopes = {record["number"]: record["capture_scope"] for record in records}
    validate_scopes(scopes)
    for record in records:
        validate_record(record)

    pr_1464 = next(record for record in records if record["number"] == LIVE_ADDITION_NUMBER)
    if len(pr_1464["commits"]) != 50:
        raise RuntimeError(f"PR #1464 expected 50 commits, found {len(pr_1464['commits'])}")

    markdown = MARKDOWN_PATH.read_text(encoding="utf-8")
    markdown_numbers = [int(match.group(1)) for match in PR_BLOCK_RE.finditer(markdown)]
    if len(markdown_numbers) != 710 or len(set(markdown_numbers)) != 710:
        raise RuntimeError("Markdown no longer has exactly 710 unique PR records")
    if "inaccessible for this record" in markdown:
        raise RuntimeError("Markdown still contains inaccessible detail records")
    for number in numbers:
        block = next(
            match.group(0)
            for match in PR_BLOCK_RE.finditer(markdown)
            if int(match.group(1)) == number
        )
        if "pr_detail_inventory.jsonl" not in block:
            raise RuntimeError(f"PR #{number}: Markdown does not link the companion")

    serialized = COMPANION_PATH.read_bytes()
    return {
        "companion_records": len(records),
        "historical_gap_records": sum(
            record["capture_scope"] == HISTORICAL_SCOPE for record in records
        ),
        "jsonl_bytes": len(serialized),
        "jsonl_sha256": hashlib.sha256(serialized).hexdigest(),
        "markdown_records": len(markdown_numbers),
        "pr_1464_commits": len(pr_1464["commits"]),
    }


def main() -> None:
    args = parse_args()
    if args.workers < 1 or args.workers > 8:
        raise SystemExit("--workers must be between 1 and 8")
    if args.validate_only:
        print(json.dumps(validate_outputs(), sort_keys=True))
        return

    markdown = MARKDOWN_PATH.read_text(encoding="utf-8")
    scopes = discover_scopes(markdown)
    token = read_token()
    records: list[dict[str, Any]] = []
    with ThreadPoolExecutor(max_workers=args.workers) as executor:
        futures = {
            executor.submit(fetch_record, number, scope, token): number
            for number, scope in sorted(scopes.items())
        }
        for index, future in enumerate(as_completed(futures), 1):
            number = futures[future]
            records.append(future.result())
            if index % 20 == 0 or index == len(futures):
                print(f"fetched {index}/{len(futures)} PRs (last #{number})", flush=True)

    records.sort(key=lambda record: record["number"])
    captured_at = datetime.now(UTC).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    write_companion(records)
    updated = rewrite_record_blocks(markdown, records)
    updated = rewrite_header(updated, captured_at)
    atomic_write(MARKDOWN_PATH, updated)
    print(json.dumps(validate_outputs(), sort_keys=True))


if __name__ == "__main__":
    main()
