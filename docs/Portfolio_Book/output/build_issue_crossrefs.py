#!/usr/bin/env python3
"""Reconcile issue records with local commit messages and the PR inventory.

The GitHub ``closedByPullRequestsReferences`` relation remains the authority for
formal closure links.  This script adds a separate, explicitly non-formal index
of textual issue references found in:

* every commit subject/body reachable through ``git rev-list --all``; and
* every PR record currently retained in ``research/pr_inventory.md``.

It intentionally does not infer acceptance-criteria completion from a textual
reference.  Run with ``--check`` to verify that the checked-in inventory is the
deterministic result for the current local refs and PR inventory.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[3]
RESEARCH_DIR = Path(__file__).resolve().parent / "research"
ISSUE_INVENTORY = RESEARCH_DIR / "issue_inventory.md"
PR_INVENTORY = RESEARCH_DIR / "pr_inventory.md"

ISSUE_HEADING_RE = re.compile(r"^### Issue #(\d+)\b", re.MULTILINE)
ISSUE_BLOCK_RE = re.compile(
    r"^### Issue #(\d+)\b.*?(?=^### Issue #|\Z)", re.MULTILINE | re.DOTALL
)
PR_BLOCK_RE = re.compile(
    r"^### PR #(\d+)\b.*?(?=^### PR #|\Z)", re.MULTILINE | re.DOTALL
)
EXPLICIT_ISSUE_RE = re.compile(
    r"(?<![\w])#(\d+)\b|(?:issues?|이슈)[\s:_/-]*#?(\d+)\b", re.IGNORECASE
)
NUMERIC_SCOPE_ISSUE_RE = re.compile(
    r"\b(?:build|chore|ci|docs|feat|fix|perf|refactor|revert|style|test)"
    r"\((\d+)\):",
    re.IGNORECASE,
)
FOUR_DIGIT_TRAILING_ISSUE_RE = re.compile(r"\((\d{4,})\)\s*$")
PR_NUMBER_RE = re.compile(
    r"(?<![\w])#(\d+)\b|(?:PR|pull requests?)[\s:_/-]*#?(\d+)\b",
    re.IGNORECASE,
)
CROSSREF_LINE_RE = re.compile(
    r"^- Repository cross-reference \(textual; non-formal\):.*\n?", re.MULTILINE
)
SUMMARY_RE = re.compile(
    r"<!-- ISSUE_CROSSREF_SUMMARY_START -->.*?"
    r"<!-- ISSUE_CROSSREF_SUMMARY_END -->\n*",
    re.DOTALL,
)
SUMMARY_START = "<!-- ISSUE_CROSSREF_SUMMARY_START -->"
SUMMARY_END = "<!-- ISSUE_CROSSREF_SUMMARY_END -->"
SHORT_HASH_LENGTH = 12
EXPECTED_ISSUE_COUNT = 752


@dataclass(frozen=True)
class CommitRecord:
    sha: str
    subject: str
    body: str


@dataclass(frozen=True)
class PullRequestRecord:
    number: int
    status: str
    block: str


def run_git(*args: str) -> bytes:
    return subprocess.check_output(["git", *args], cwd=REPO_ROOT)


def load_issue_numbers(text: str) -> list[int]:
    numbers = [int(value) for value in ISSUE_HEADING_RE.findall(text)]
    if len(numbers) != EXPECTED_ISSUE_COUNT:
        raise ValueError(
            f"expected {EXPECTED_ISSUE_COUNT} issue records, found {len(numbers)}"
        )
    if len(set(numbers)) != len(numbers):
        raise ValueError("duplicate issue headings found")
    return numbers


def parse_commits() -> list[CommitRecord]:
    raw = run_git("log", "--all", "--format=%H%x1f%s%x1f%b%x1e").decode(
        "utf-8", errors="replace"
    )
    records: list[CommitRecord] = []
    for raw_record in raw.split("\x1e"):
        record = raw_record.lstrip("\n")
        if not record:
            continue
        parts = record.split("\x1f", 2)
        if len(parts) != 3:
            raise ValueError("unexpected git log record framing")
        sha, subject, body = parts
        if not re.fullmatch(r"[0-9a-f]{40}", sha):
            raise ValueError(f"unexpected commit hash: {sha!r}")
        records.append(CommitRecord(sha=sha, subject=subject, body=body))

    reachable = {
        line.decode("ascii")
        for line in run_git("rev-list", "--all").splitlines()
        if line
    }
    parsed = {record.sha for record in records}
    if parsed != reachable:
        raise ValueError(
            "git log/rev-list mismatch: "
            f"missing={len(reachable - parsed)}, extra={len(parsed - reachable)}"
        )
    prefixes = {sha[:SHORT_HASH_LENGTH] for sha in reachable}
    if len(prefixes) != len(reachable):
        raise ValueError(f"{SHORT_HASH_LENGTH}-character commit prefix collision")
    return records


def pr_status(block: str) -> str:
    detailed = re.search(
        r"^- author/state/dates:.*?\|\s*([A-Z_-]+)\s*\|", block, re.MULTILINE
    )
    if detailed:
        return detailed.group(1)
    if re.search(r"\bmerged yes(?:/|\b)", block, re.IGNORECASE):
        return "MERGED"
    if re.search(r"\bmerged no(?:/|\b)", block, re.IGNORECASE):
        return "CLOSED_UNMERGED"
    if re.search(r"^- author .*?;\s*open;", block, re.MULTILINE | re.IGNORECASE):
        return "OPEN"
    return "UNKNOWN"


def parse_pull_requests(text: str) -> list[PullRequestRecord]:
    records = [
        PullRequestRecord(
            number=int(match.group(1)),
            status=pr_status(match.group(0)),
            block=match.group(0),
        )
        for match in PR_BLOCK_RE.finditer(text)
    ]
    numbers = [record.number for record in records]
    if len(numbers) != len(set(numbers)):
        raise ValueError("duplicate PR headings found")
    return records


def explicit_issue_numbers(text: str, issue_numbers: set[int]) -> set[int]:
    matches = {int(first or second) for first, second in EXPLICIT_ISSUE_RE.findall(text)}
    return matches & issue_numbers


def title_tag_numbers(title: str, issue_numbers: set[int]) -> set[int]:
    # Numeric conventional-commit scopes (``fix(1019):``) are issue-shaped in
    # this repository.  A bare trailing tag is accepted only for four-or-more
    # digit values, which covers the late ``(1423)`` convention without
    # misclassifying sizes such as ``Semaphore(64)`` or ``VARCHAR(64)``.
    matches = {
        int(value) for value in NUMERIC_SCOPE_ISSUE_RE.findall(title)
    }
    matches.update(
        int(value) for value in FOUR_DIGIT_TRAILING_ISSUE_RE.findall(title)
    )
    return matches & issue_numbers


def commit_mentions(
    commits: list[CommitRecord], issue_numbers: set[int]
) -> dict[int, set[str]]:
    mentions = {number: set() for number in issue_numbers}
    for commit in commits:
        numbers = explicit_issue_numbers(
            f"{commit.subject}\n{commit.body}", issue_numbers
        )
        numbers.update(title_tag_numbers(commit.subject, issue_numbers))

        # A GitHub merge commit stores the merged PR title as its first body line.
        # Numeric title conventions are accepted only in title positions to avoid
        # treating arbitrary numeric parentheses in prose as issue references.
        if commit.subject.startswith("Merge pull request #"):
            first_body_line = commit.body.splitlines()[0] if commit.body else ""
            numbers.update(title_tag_numbers(first_body_line, issue_numbers))

        for number in numbers:
            mentions[number].add(commit.sha)
    return mentions


def pr_mentions(
    pull_requests: list[PullRequestRecord], issue_numbers: set[int]
) -> dict[int, set[int]]:
    mentions = {number: set() for number in issue_numbers}
    for pull_request in pull_requests:
        title = pull_request.block.splitlines()[0]
        numbers = explicit_issue_numbers(pull_request.block, issue_numbers)
        numbers.update(title_tag_numbers(title, issue_numbers))
        for number in numbers:
            mentions[number].add(pull_request.number)
    return mentions


def formal_links_from_pr_inventory(
    pull_requests: list[PullRequestRecord], issue_numbers: set[int]
) -> dict[int, set[int]]:
    links = {number: set() for number in issue_numbers}
    for pull_request in pull_requests:
        linked = re.search(
            r"linked issues:\s*(\d+)\s*\[([^\]]*)\]",
            pull_request.block,
            re.IGNORECASE,
        )
        if not linked:
            continue
        values = re.findall(r"#(\d+)\b", linked.group(2))
        if int(linked.group(1)) != len(values):
            raise ValueError(
                f"PR #{pull_request.number} linked-issue count/list mismatch"
            )
        for value in values:
            number = int(value)
            if number in issue_numbers:
                links[number].add(pull_request.number)
    return links


def formal_links_from_issue_block(block: str) -> set[int]:
    linked = re.search(r"Linked PRs:\s*(\d+)\s*\[([^\]]*)\]", block)
    if not linked:
        raise ValueError("issue record has no Linked PRs field")
    values = re.findall(r"#(\d+)/", linked.group(2))
    if int(linked.group(1)) != len(values):
        raise ValueError("issue Linked PRs count/list mismatch")
    return {int(value) for value in values}


def textual_pr_mentions_from_issue_inventory(
    issue_text: str, available_pr_numbers: set[int]
) -> dict[int, set[int]]:
    """Return non-formal PR numbers mentioned by issue source text/comments.

    Generated cross-reference and interpretation lines are removed before the
    scan so rerunning the generator cannot feed its own output back into the
    evidence. Formal ``Linked PRs`` values are also removed from this category.
    """

    mentions: dict[int, set[int]] = {}
    for match in ISSUE_BLOCK_RE.finditer(issue_text):
        issue_number = int(match.group(1))
        block = CROSSREF_LINE_RE.sub("", match.group(0))
        block = re.sub(r"^- Root cause:.*$", "", block, flags=re.MULTILINE)
        values = {
            int(first or second)
            for first, second in PR_NUMBER_RE.findall(block)
        }
        mentions[issue_number] = (
            values & available_pr_numbers
        ) - formal_links_from_issue_block(block)
    return mentions


def validate_formal_links(
    issue_text: str,
    formal_pr_links: dict[int, set[int]],
    pr_by_number: dict[int, PullRequestRecord],
) -> int:
    issue_blocks = {
        int(match.group(1)): match.group(0) for match in ISSUE_BLOCK_RE.finditer(issue_text)
    }
    formal_count = 0
    issue_pairs: set[tuple[int, int]] = set()
    for issue_number, block in issue_blocks.items():
        issue_links = formal_links_from_issue_block(block)
        unavailable = issue_links - pr_by_number.keys()
        if unavailable:
            raise ValueError(
                f"issue #{issue_number} names unavailable formal PRs: {sorted(unavailable)}"
            )
        for pr_number in issue_links:
            if pr_by_number[pr_number].status != "MERGED":
                raise ValueError(
                    f"issue #{issue_number} formal PR #{pr_number} is not merged "
                    f"in PR inventory ({pr_by_number[pr_number].status})"
                )
            issue_pairs.add((issue_number, pr_number))
        formal_count += len(issue_links)
    pr_pairs = {
        (issue_number, pr_number)
        for issue_number, pr_numbers in formal_pr_links.items()
        for pr_number in pr_numbers
    }
    if issue_pairs != pr_pairs:
        raise ValueError(
            "formal-link mismatch between issue and PR inventories: "
            f"issue-only={sorted(issue_pairs - pr_pairs)}, "
            f"PR-only={sorted(pr_pairs - issue_pairs)}"
        )
    return formal_count


def crossref_line(
    issue_number: int,
    formal_refs: set[int],
    issue_text_pr_refs: dict[int, set[int]],
    commit_refs: dict[int, set[str]],
    pr_refs: dict[int, set[int]],
    pr_by_number: dict[int, PullRequestRecord],
) -> str:
    commits = sorted(commit_refs[issue_number])
    pull_requests = sorted(pr_refs[issue_number])
    commit_values = ", ".join(sha[:SHORT_HASH_LENGTH] for sha in commits) or "none"
    pr_values = (
        ", ".join(
            f"#{number}/{pr_by_number[number].status}" for number in pull_requests
        )
        or "none"
    )
    formal_values = (
        ", ".join(
            f"#{number}/{pr_by_number[number].status}" for number in sorted(formal_refs)
        )
        or "none"
    )
    issue_text_values = (
        ", ".join(
            f"#{number}/{pr_by_number[number].status}"
            for number in sorted(issue_text_pr_refs[issue_number])
        )
        or "none"
    )
    return (
        "- Repository cross-reference (textual; non-formal): "
        f"formal closed-by PRs {len(formal_refs)} [{formal_values}]; "
        "issue-record textual PR mentions "
        f"{len(issue_text_pr_refs[issue_number])} [{issue_text_values}]; "
        f"reachable commit-message refs {len(commits)} [{commit_values}]; "
        f"PR-record issue refs {len(pull_requests)} [{pr_values}]."
    )


def portfolio_class(block: str) -> str:
    match = re.search(r"Portfolio:\s*(.+?)\s+case\.", block)
    if not match:
        raise ValueError("issue record has no portfolio classification")
    return match.group(1)


def issue_240_resolution(
    block: str,
    commit_refs: dict[int, set[str]],
    pr_refs: dict[int, set[int]],
    pr_by_number: dict[int, PullRequestRecord],
) -> str:
    commits = commit_refs[240]
    pull_requests = sorted(pr_refs[240])
    if 242 not in pull_requests:
        raise ValueError("minimum issue #240 audit failed: PR #242 reference not found")
    merged = [
        number for number in pull_requests if pr_by_number[number].status == "MERGED"
    ]
    if not commits or not merged:
        raise ValueError("minimum issue #240 audit found no commit/merged-PR evidence")
    pr_values = ", ".join(f"#{number}" for number in merged)
    return (
        "- Root cause: reported in issue text only; not independently verified here. "
        "Resolution: no formal GitHub closed-by PR is present, but repository "
        f"cross-reference finds {len(commits)} reachable commit-message references and {len(merged)} "
        f"merged PR records ({pr_values}). These independently show implementation "
        "activity associated with #240; they do not prove every ETL acceptance "
        f"criterion was completed. Portfolio: {portfolio_class(block)} case."
    )


def render_issue_blocks(
    text: str,
    issue_text_pr_refs: dict[int, set[int]],
    commit_refs: dict[int, set[str]],
    pr_refs: dict[int, set[int]],
    pr_by_number: dict[int, PullRequestRecord],
) -> str:
    def replace(match: re.Match[str]) -> str:
        issue_number = int(match.group(1))
        block = CROSSREF_LINE_RE.sub("", match.group(0))
        formal_refs = formal_links_from_issue_block(block)
        line = crossref_line(
            issue_number,
            formal_refs,
            issue_text_pr_refs,
            commit_refs,
            pr_refs,
            pr_by_number,
        )
        root_marker = "- Root cause:"
        if root_marker not in block:
            raise ValueError(f"issue #{issue_number} has no root-cause line")
        block = block.replace(root_marker, f"{line}\n{root_marker}", 1)
        if issue_number == 240:
            block = re.sub(
                r"^- Root cause:.*$",
                issue_240_resolution(
                    block, commit_refs, pr_refs, pr_by_number
                ),
                block,
                count=1,
                flags=re.MULTILINE,
            )
        return block

    return ISSUE_BLOCK_RE.sub(replace, text)


def summary_block(
    commit_count: int,
    pr_count: int,
    issue_numbers: set[int],
    issue_text_pr_refs: dict[int, set[int]],
    commit_refs: dict[int, set[str]],
    pr_refs: dict[int, set[int]],
    formal_link_count: int,
) -> str:
    commit_issues = sum(bool(commit_refs[number]) for number in issue_numbers)
    pr_issues = sum(bool(pr_refs[number]) for number in issue_numbers)
    issue_text_pr_issues = sum(
        bool(issue_text_pr_refs[number]) for number in issue_numbers
    )
    union_issues = sum(
        bool(
            commit_refs[number]
            or pr_refs[number]
            or issue_text_pr_refs[number]
        )
        for number in issue_numbers
    )
    return (
        f"{SUMMARY_START}\n"
        "## Repository cross-reference audit\n\n"
        f"- Local reachability scan: {commit_count:,} unique commits from `git rev-list --all`; "
        f"{commit_issues} issues have {sum(map(len, commit_refs.values())):,} unique "
        "commit-message references.\n"
        f"- Available PR inventory scan: {pr_count} unique PR records; {pr_issues} issues "
        f"have {sum(map(len, pr_refs.values())):,} unique textual PR references.\n"
        f"- Issue-record text scan: {issue_text_pr_issues} issues contain "
        f"{sum(map(len, issue_text_pr_refs.values())):,} non-formal PR-number mentions "
        "that resolve to available PR records.\n"
        f"- Union: {union_issues} issues have at least one repository cross-reference; "
        f"{len(issue_numbers) - union_issues} have none. Formal issue-to-PR links remain "
        f"separate ({formal_link_count} links in issue records).\n"
        "- Match rules: explicit `#N`, labeled `Issue N`/`issue-N`/`Issues: N`/`이슈 N`, "
        "numeric conventional-commit scope such as `fix(1019):`, and a four-or-more-digit "
        "trailing title tag such as `(1423)`. Matches are deduplicated per commit/PR.\n"
        "- Safeguard: a textual reference is navigation evidence only. It is not promoted "
        "to GitHub closed-by semantics and does not by itself prove root cause, runtime "
        "effect, or completion of every acceptance criterion.\n"
        f"{SUMMARY_END}\n\n"
    )


def render_inventory(
    issue_text: str,
    commits: list[CommitRecord],
    pull_requests: list[PullRequestRecord],
    issue_numbers: set[int],
    issue_text_pr_refs: dict[int, set[int]],
    commit_refs: dict[int, set[str]],
    pr_refs: dict[int, set[int]],
    formal_link_count: int,
) -> str:
    pr_by_number = {record.number: record for record in pull_requests}
    rendered = render_issue_blocks(
        issue_text, issue_text_pr_refs, commit_refs, pr_refs, pr_by_number
    )
    rendered = SUMMARY_RE.sub("", rendered)
    summary = summary_block(
        len(commits),
        len(pull_requests),
        issue_numbers,
        issue_text_pr_refs,
        commit_refs,
        pr_refs,
        formal_link_count,
    )
    marker = "## Interpretation safeguards"
    if marker not in rendered:
        raise ValueError(f"missing insertion marker: {marker}")
    rendered = rendered.replace(marker, f"{summary}{marker}", 1)
    rendered = rendered.replace(
        "- Textual references to PR/commit numbers are not promoted to formal links; "
        "formal linkage is only GitHub’s closed-by-PR relation.",
        "- Textual references to issues from commits/PRs are reported separately as "
        "repository cross-references and are not promoted to formal links; formal "
        "linkage is only GitHub’s closed-by-PR relation.",
        1,
    )
    return rendered


def validate_rendered(text: str) -> None:
    numbers = load_issue_numbers(text)
    crossref_count = len(CROSSREF_LINE_RE.findall(text))
    if crossref_count != EXPECTED_ISSUE_COUNT:
        raise ValueError(
            f"expected {EXPECTED_ISSUE_COUNT} cross-reference lines, found {crossref_count}"
        )
    if text.count(SUMMARY_START) != 1 or text.count(SUMMARY_END) != 1:
        raise ValueError("cross-reference summary marker count is not exactly one")
    if len(set(numbers)) != EXPECTED_ISSUE_COUNT:
        raise ValueError("rendered issue inventory lost unique records")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--check",
        action="store_true",
        help="fail if issue_inventory.md is not the deterministic rendered result",
    )
    args = parser.parse_args()

    issue_text = ISSUE_INVENTORY.read_text(encoding="utf-8")
    pr_text = PR_INVENTORY.read_text(encoding="utf-8")
    issue_number_list = load_issue_numbers(issue_text)
    issue_numbers = set(issue_number_list)
    commits = parse_commits()
    pull_requests = parse_pull_requests(pr_text)
    commit_refs = commit_mentions(commits, issue_numbers)
    pr_refs = pr_mentions(pull_requests, issue_numbers)
    formal_pr_links = formal_links_from_pr_inventory(
        pull_requests, issue_numbers
    )
    pr_by_number = {record.number: record for record in pull_requests}
    issue_text_pr_refs = textual_pr_mentions_from_issue_inventory(
        issue_text, set(pr_by_number)
    )
    formal_link_count = validate_formal_links(
        issue_text,
        formal_pr_links,
        pr_by_number,
    )
    rendered = render_inventory(
        issue_text,
        commits,
        pull_requests,
        issue_numbers,
        issue_text_pr_refs,
        commit_refs,
        pr_refs,
        formal_link_count,
    )
    validate_rendered(rendered)

    if args.check:
        if rendered != issue_text:
            print(
                f"OUTDATED: {ISSUE_INVENTORY.relative_to(REPO_ROOT)}; "
                "run build_issue_crossrefs.py",
                file=sys.stderr,
            )
            return 1
    elif rendered != issue_text:
        ISSUE_INVENTORY.write_text(rendered, encoding="utf-8", newline="\n")

    linked_union = sum(
        bool(
            commit_refs[number]
            or pr_refs[number]
            or issue_text_pr_refs[number]
        )
        for number in issue_numbers
    )
    print(
        "OK: "
        f"issues={len(issue_numbers)}, commits={len(commits)}, "
        f"prs={len(pull_requests)}, crossref_issues={linked_union}, "
        f"formal_links={formal_link_count}, "
        f"issue_240_commits={len(commit_refs[240])}, "
        f"issue_240_prs={len(pr_refs[240])}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
