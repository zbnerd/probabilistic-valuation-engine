#!/usr/bin/env python3
"""Build a safe, diff-backed inventory for every commit reachable from refs.

Policy: normal commits are compared with their only/first parent; merge commits
are deliberately compared with their first parent; a root commit is compared
with Git's empty tree.  The semantic columns are conservative descriptions of
the commit subject, changed paths, and hunk identifiers, not runtime claims.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import re
import subprocess
import sys
from pathlib import Path
from typing import Iterable


REPO = Path(__file__).resolve().parents[3]
OUTPUT = REPO / "docs/Portfolio_Book/output/research/commit_inventory.csv"
EMPTY_TREE = "4b825dc642cb6eb9a060e54bf8d69288fbee4904"
HEADERS = [
    "commit_hash",
    "authored_date",
    "author_name",
    "subject",
    "changed_files",
    "additions",
    "deletions",
    "merge_or_revert",
    "diff_based_summary",
    "related_feature_problem_design",
    "portfolio_value",
]
CONTROL = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")
EMAIL = re.compile(r"(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b")
URL = re.compile(r"(?i)\b(?:https?|ssh)://[^\s]+")
IPV4 = re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b")
SECRET = re.compile(
    r"(?i)\b(?:api[_ -]?key|secret|password|access[_ -]?token|private[_ -]?key)\b\s*[:=]\s*[^\s,;]+"
)
SYMBOL = re.compile(
    r"^\s*(?:public\s+|private\s+|protected\s+|internal\s+|abstract\s+|final\s+|"
    r"override\s+|suspend\s+|async\s+|static\s+)*(?:class|interface|object|enum|fun|"
    r"function|def|record|data\s+class)\s+([A-Za-z_][A-Za-z0-9_]*)"
)
CONFIG_KEY = re.compile(r"^\s*([A-Za-z][A-Za-z0-9_.-]{1,70})\s*:")


def git(*args: str) -> str:
    return subprocess.check_output(
        ["git", *args], cwd=REPO, text=True, encoding="utf-8", errors="replace"
    )


def safe(value: str) -> str:
    """Prevent spreadsheet formulas and redact addresses/credential-looking values."""
    value = CONTROL.sub(" ", value).replace("\r", " ").replace("\n", " ").strip()
    value = SECRET.sub("[REDACTED_SECRET]", value)
    value = EMAIL.sub("[REDACTED_EMAIL]", value)
    value = URL.sub("[REDACTED_URL]", value)
    value = IPV4.sub("[REDACTED_IP]", value)
    if value[:1] in ("=", "+", "-", "@"):
        value = "'" + value
    return value


def commit_hashes() -> list[str]:
    return sorted(set(git("rev-list", "--all").splitlines()))


def parents(commit: str) -> list[str]:
    return git("show", "-s", "--format=%P", commit).strip().split()


def comparison_base(commit: str) -> tuple[str, str]:
    parent_list = parents(commit)
    if not parent_list:
        return EMPTY_TREE, "root:empty-tree"
    if len(parent_list) > 1:
        return parent_list[0], "merge:first-parent"
    return parent_list[0], "parent"


def name_status(base: str, commit: str) -> list[str]:
    raw = git("diff", "--name-status", "-M", "--no-ext-diff", base, commit)
    values: list[str] = []
    for line in raw.splitlines():
        bits = line.split("\t")
        status = bits[0] if bits else "?"
        if status.startswith(("R", "C")) and len(bits) >= 3:
            values.append(f"{status}:{bits[1]} -> {bits[2]}")
        elif len(bits) >= 2:
            values.append(f"{status}:{bits[1]}")
    return values


def numstat(base: str, commit: str) -> tuple[int, int]:
    additions = deletions = 0
    for line in git("diff", "--numstat", "--no-ext-diff", base, commit).splitlines():
        bits = line.split("\t", 2)
        if len(bits) < 2:
            continue
        additions += int(bits[0]) if bits[0].isdigit() else 0
        deletions += int(bits[1]) if bits[1].isdigit() else 0
    return additions, deletions


def hunk_identifiers(base: str, commit: str) -> list[str]:
    """Stream the whole patch; retain only harmless identifier-sized evidence."""
    command = ["git", "diff", "--unified=0", "--no-ext-diff", base, commit]
    process = subprocess.Popen(
        command,
        cwd=REPO,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    assert process.stdout is not None
    found: list[str] = []
    seen: set[str] = set()
    for line in process.stdout:
        candidate = ""
        if line.startswith("@@"):
            tail = line.rsplit("@@", 1)[-1].strip()
            match = SYMBOL.match(tail)
            candidate = match.group(1) if match else ""
        elif line.startswith(("+", "-")) and not line.startswith(("+++", "---")):
            body = line[1:]
            match = SYMBOL.match(body) or CONFIG_KEY.match(body)
            candidate = match.group(1) if match else ""
        if candidate and candidate not in seen:
            seen.add(candidate)
            found.append(candidate)
            if len(found) == 6:
                # Keep reading: every actual diff is inspected, only retention is capped.
                continue
    if process.wait() != 0:
        raise RuntimeError(f"git diff failed for {commit}")
    return [safe(item) for item in found[:6]]


def path_from_entry(entry: str) -> str:
    return entry.split(":", 1)[-1].split(" -> ")[-1]


def is_test_path(path: str) -> bool:
    return bool(re.search(r"(?:^|/)(?:src/)?(?:test|tests|testFixtures)(?:/|$)", path, re.I))


def is_documentation_path(path: str) -> bool:
    return bool(re.search(r"(?:^|/)docs?(?:/|$)|(?:^|/)README(?:\.[^/]*)?$|\.md$", path, re.I))


def category(subject: str, paths: list[str]) -> str:
    """Classify the implementation domain without letting a companion test win."""
    file_paths = [path_from_entry(entry) for entry in paths]
    implementation_paths = [path for path in file_paths if not is_test_path(path)]
    if file_paths and not implementation_paths:
        return "testing/quality"

    non_doc_paths = [path for path in implementation_paths if not is_documentation_path(path)]
    # ADR/report companions are evidence, not the implementation domain, when
    # a commit also changes production/build paths.
    domain_paths = non_doc_paths or implementation_paths or file_paths
    path_corpus = " ".join(domain_paths).lower()
    subject_corpus = subject.lower()
    rules = [
        ("security/auth", ("security", "auth", "jwt", "oauth", "permission", "rate-limit")),
        ("messaging/idempotency", ("kafka", "pgmq", "queue", "event", "inbox", "outbox", "idempot")),
        ("storage/artifacts", ("minio", "objectstorage", "object-storage", "artifact", "gzip", "s3client")),
        ("data/persistence", ("migration", "postgres", "jpa", "repository", "database", "sql", "redis")),
        ("performance/throughput", ("perf", "performance", "throughput", "benchmark", "load-test", "cache")),
        ("async/concurrency", ("async", "concurr", "executor", "coroutine", "semaphore", "retry", "timeout")),
        ("API/interface", ("controller", "api", "endpoint", "web", "openapi", "dto")),
        ("build/operations", ("gradle", "docker", "compose", "workflow", "deploy", "ci", "config")),
        ("documentation", ("docs/", ".md", "adr", "readme", "document")),
    ]
    scores = []
    for order, (label, needles) in enumerate(rules):
        path_hits = sum(path_corpus.count(needle) for needle in needles)
        subject_hits = sum(subject_corpus.count(needle) for needle in needles)
        scores.append((path_hits * 3 + subject_hits, -order, label))
    score, _, label = max(scores)
    if score:
        return label
    if any(is_test_path(path) for path in file_paths):
        # Mixed commits with an otherwise unclassified implementation retain
        # the implementation/domain label; tests remain validation evidence.
        return "application/domain"
    return "application/domain"


def changed_components(paths: list[str]) -> list[str]:
    """Return stable component labels from actual changed paths."""
    components: list[str] = []
    for entry in paths:
        path = path_from_entry(entry)
        parts = [part for part in path.split("/") if part]
        if not parts:
            continue
        if parts[0].startswith("module-"):
            component = parts[0]
        elif parts[0] == "docs" and len(parts) > 1:
            component = f"docs/{parts[1]}"
        elif parts[0].startswith(".") and len(parts) > 1:
            component = f"{parts[0]}/{parts[1]}"
        else:
            component = parts[0]
        if component not in components:
            components.append(component)
    return components[:4]


def semantic_fields(
    subject: str,
    paths: list[str],
    additions: int,
    deletions: int,
    policy: str,
    identifiers: list[str],
) -> tuple[str, str, str]:
    domain = category(subject, paths)
    path_sample = "; ".join(paths[:3]) if paths else "no file-level change"
    identifier_text = ", ".join(identifiers) if identifiers else "no stable hunk identifier retained"
    summary = (
        f"{policy} diff: {len(paths)} file(s), +{additions}/-{deletions}; "
        f"path evidence {path_sample}; hunk identifiers {identifier_text}."
    )
    relation = (
        f"{domain}: "
        + (
            f"actual diff connects intent '{safe(subject)[:120]}' to component(s) "
            f"{', '.join(changed_components(paths)) or 'unclassified'}; "
            f"patch symbols/keys: {identifier_text}. Runtime outcome is not inferred."
            if paths
            else f"first-parent/empty-tree diff has no file delta; subject '{safe(subject)[:120]}' is intent context only."
        )
    )
    corpus = (subject + " " + " ".join(paths)).lower()
    file_paths = [path_from_entry(path) for path in paths]
    has_test = any(is_test_path(path) for path in file_paths)
    is_mechanical = policy.startswith("merge") or subject.lower().startswith("revert")
    code_paths = [
        path
        for path in file_paths
        if not re.search(r"(?:^|/)(?:docs?|\.github)(?:/|$)|\.md$", path, re.I)
    ]
    if not is_mechanical and len(code_paths) >= 2 and has_test:
        value = f"high — diff includes implementation and test-path evidence in {domain}; runtime outcome is unclaimed."
    elif not is_mechanical and code_paths:
        value = f"medium — code-path evidence supports a {domain} delivery; validation/result remains outside this commit record."
    else:
        reason = "merge/revert policy" if is_mechanical else "documentation or configuration scope"
        value = f"low — {reason}; useful as traceability, not standalone impact evidence."
    return safe(summary), safe(relation), safe(value)


def row_for(commit: str) -> list[str]:
    metadata = git("show", "-s", "--format=%aI%x00%an%x00%s", commit).rstrip("\n").split("\x00")
    if len(metadata) != 3:
        raise RuntimeError(f"unexpected metadata for {commit}")
    authored_date, author_name, subject = metadata
    base, policy = comparison_base(commit)
    paths = name_status(base, commit)
    additions, deletions = numstat(base, commit)
    identifiers = hunk_identifiers(base, commit)
    lowered = subject.lower()
    merge_or_revert = "revert" if lowered.startswith("revert") else policy
    summary, relation, value = semantic_fields(subject, paths, additions, deletions, policy, identifiers)
    return [
        commit,
        safe(authored_date),
        safe(author_name),
        safe(subject),
        safe("; ".join(paths)),
        str(additions),
        str(deletions),
        merge_or_revert,
        summary,
        relation,
        value,
    ]


def write_inventory(output: Path) -> int:
    hashes = commit_hashes()
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8", newline="") as stream:
        # LF keeps the CSV portable while also satisfying Git's default
        # whitespace checker, which treats CR at end-of-line as trailing space.
        writer = csv.writer(stream, lineterminator="\n")
        writer.writerow(HEADERS)
        for index, commit in enumerate(hashes, start=1):
            writer.writerow(row_for(commit))
            if index % 100 == 0:
                print(f"built {index}/{len(hashes)}", file=sys.stderr, flush=True)
    return len(hashes)


def validate(output: Path) -> tuple[int, str]:
    raw = output.read_bytes()
    raw.decode("utf-8", errors="strict")
    with output.open(encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream)
        if reader.fieldnames != HEADERS:
            raise ValueError(f"header mismatch: {reader.fieldnames}")
        rows = list(reader)
    hashes = [row["commit_hash"] for row in rows]
    expected = commit_hashes()
    if len(hashes) != len(set(hashes)):
        raise ValueError("duplicate commit hashes")
    if set(hashes) != set(expected):
        raise ValueError("CSV hash set does not equal git rev-list --all")
    for row in rows:
        if any(value[:1] in ("=", "+", "-", "@") for value in row.values()):
            raise ValueError(f"unsafe spreadsheet cell in {row['commit_hash']}")
        joined = " ".join(row.values())
        if EMAIL.search(joined) or URL.search(joined) or IPV4.search(joined):
            raise ValueError(f"unredacted address-like value in {row['commit_hash']}")
    category_samples: dict[str, dict[str, str]] = {}
    for row in rows:
        path_entries = row["changed_files"].split("; ") if row["changed_files"] else []
        expected_category = category(row["subject"], path_entries)
        if not row["related_feature_problem_design"].startswith(expected_category + ":"):
            raise ValueError(f"category mismatch: {row['commit_hash']}")
        category_samples.setdefault(expected_category, row)
    if len(category_samples) < 5:
        raise ValueError(f"implausibly narrow category coverage: {sorted(category_samples)}")
    regression_cases = [
        (
            "feat(infra): MinioObjectStorage full impl",
            [
                "M:module-infra/src/main/kotlin/example/MinioObjectStorage.kt",
                "A:module-infra/src/test/kotlin/example/MinioObjectStorageIT.kt",
            ],
            "storage/artifacts",
        ),
        (
            "build: add pipeline artifact module",
            [
                "A:docs/01_ADR/ADR-745-pipeline-artifact-ownership.md",
                "A:module-pipeline-artifact/build.gradle",
                "M:settings.gradle",
            ],
            "build/operations",
        ),
        ("docs: explain replay boundary", ["A:docs/architecture/replay.md"], "documentation"),
        ("test: cover retry", ["A:module-app/src/test/kotlin/example/RetryTest.kt"], "testing/quality"),
    ]
    for sample_subject, sample_paths, expected_category in regression_cases:
        actual_category = category(sample_subject, sample_paths)
        if actual_category != expected_category:
            raise ValueError(
                f"category regression: expected {expected_category}, got {actual_category}"
            )
    sample_indexes = sorted({0, len(rows) // 3, (2 * len(rows)) // 3, len(rows) - 1})
    for index in sample_indexes:
        row = rows[index]
        base, _ = comparison_base(row["commit_hash"])
        actual_paths = safe("; ".join(name_status(base, row["commit_hash"])))
        actual_add, actual_del = numstat(base, row["commit_hash"])
        if (row["changed_files"], row["additions"], row["deletions"]) != (
            actual_paths,
            str(actual_add),
            str(actual_del),
        ):
            raise ValueError(f"deterministic sample mismatch: {row['commit_hash']}")
    return len(rows), hashlib.sha256(raw).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=OUTPUT)
    parser.add_argument("--validate-only", action="store_true")
    args = parser.parse_args()
    if not args.validate_only:
        built = write_inventory(args.output)
        print(f"built_rows={built}")
    count, digest = validate(args.output)
    print(f"validated_rows={count} sha256={digest} samples=4")


if __name__ == "__main__":
    main()
