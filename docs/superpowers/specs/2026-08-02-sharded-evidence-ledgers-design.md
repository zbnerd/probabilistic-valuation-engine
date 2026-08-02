# Sharded Evidence Ledgers Design

**Status:** proposed for written-spec review

**Amends:** `docs/superpowers/specs/2026-08-01-exhaustive-portfolio-rebuild-design.md`

**Applies to:** the capture, classification, and release ledgers under
`docs/Portfolio_Book/output/research/`

## 1. Context

The exhaustive capture correctly contains far more evidence than one GitHub-safe
JSONL blob can hold. Measurements from the immutable safe archives are:

- `document_claim_inventory.jsonl`: 520,127 canonical records and exactly
  468,435,571 uncompressed canonical JSONL bytes.
- GitHub safe JSON values alone: 208,487,523 bytes before `SourceRecord`
  metadata, stored-member locators, or explicit relations are added. Therefore
  `source_records.jsonl` is necessarily larger than the 95,000,000-byte
  publication blocker.
- Pull-request safe JSON values alone: 182,720,423 bytes before inventory
  metadata. Therefore `pr_inventory.jsonl` is also necessarily larger than the
  blocker.

The source plan names stable logical paths such as `source_records.jsonl`, and
the downstream case/content plan passes those paths repeatedly. Replacing every
consumer path would be error-prone. The approved design therefore keeps each
logical path stable and makes sharding transparent through `canonical_io`.

This change does not reduce, sample, summarize, or reclassify evidence. It only
changes the deterministic storage representation of oversized safe ledgers.

## 2. Goals and non-goals

### Goals

1. Preserve every canonical record and its exact global order.
2. Keep existing logical `.jsonl` paths valid for all downstream commands.
3. Keep every Git blob below 95,000,000 bytes and every compressed shard at or
   below 90,000,000 bytes.
4. Bind record counts, byte counts, order, and hashes so missing, duplicated,
   reordered, corrupted, substituted, or extra shards fail verification.
5. Publish or roll back a whole logical ledger as one transaction.
6. Reuse the same mechanism for later large ledgers such as
   `classified_source_records.jsonl` without changing their callers.

### Non-goals

- No Git LFS dependency.
- No raw external PDF, secret, contact, patch, or AI-trace payload is added.
- No relation, fact, status, or classification inference is introduced.
- Existing deterministic tar volumes remain unchanged and are not repacked into
  these ledgers.
- Small JSONL files are not compressed merely for uniformity.

## 3. Chosen representation

`canonical_io.write_jsonl()` retains plain canonical JSONL when its complete
canonical byte stream is at most 50,000,000 bytes. It removes any stale shards
owned by that same logical path after successful publication.

When the stream exceeds 50,000,000 bytes, the original logical path becomes a
one-line canonical JSONL index. For example:

```text
source_records.jsonl
source_records-part-001.jsonl.gz
source_records-part-002.jsonl.gz
...
```

The index has this contract:

```json
{
  "artifact_format": "canonical-jsonl-gzip-shards-v1",
  "canonical_byte_count": 0,
  "canonical_sha256": "<64 lowercase hex>",
  "compression": "gzip-9-mtime-0",
  "logical_path": "source_records.jsonl",
  "record_count": 0,
  "record_type": "SourceRecord",
  "schema_version": 1,
  "shards": []
}
```

Each `shards` entry contains:

```json
{
  "compressed_byte_count": 0,
  "compressed_sha256": "<64 lowercase hex>",
  "first_identity": "<source_id or claim_id>",
  "last_identity": "<source_id or claim_id>",
  "ordinal": 1,
  "path": "source_records-part-001.jsonl.gz",
  "record_count": 0,
  "uncompressed_byte_count": 0,
  "uncompressed_sha256": "<64 lowercase hex>"
}
```

`canonical_byte_count` and `canonical_sha256` identify the exact concatenation
of every uncompressed canonical record line, including each trailing LF, in
shard order. The index itself ends with one LF.

## 4. Deterministic shard construction

Records remain in the order supplied to `write_jsonl`; the writer never chooses
a new semantic order. Existing capture callers continue to pass UTF-8-ID-sorted
sources and claims, and specialized inventories retain that source order.

The writer serializes every record with:

```python
json.dumps(
    item.to_dict(),
    ensure_ascii=False,
    sort_keys=True,
    separators=(",", ":"),
).encode("utf-8") + b"\n"
```

Whole record lines are accumulated in order until adding the next line would
exceed 50,000,000 uncompressed bytes. A record line is never split. A single
line larger than the target forms a one-record shard, but publication still
fails if its compressed shard exceeds 90,000,000 bytes.

Gzip output uses `compresslevel=9`, an empty embedded filename, and `mtime=0`.
Shard ordinals are zero-padded to three digits and are assigned only from final
record order. A logical ledger requiring more than 999 shards blocks instead of
changing the filename grammar. Identical inputs under the locked
Python/toolchain produce byte-identical index and shard files.

Every rendered shard is checked before publication:

- compressed byte count is at most 90,000,000;
- compressed SHA-256 matches the index;
- gzip header time is zero;
- streamed decompression yields the recorded uncompressed byte count and hash;
- complete shard concatenation yields the logical count, byte count, and hash.

## 5. Transparent reading and validation

`canonical_io.read_jsonl(path, Model)` examines the first object at the logical
path.

- A normal model object follows the existing plain-JSONL path.
- An exact `artifact_format=canonical-jsonl-gzip-shards-v1` object must be the
  only line and is resolved as the shard index.
- Any near-match, unknown version, mixed index/record file, or malformed index
  is rejected rather than interpreted heuristically.

Index validation requires:

1. `logical_path` equals the requested basename and `record_type` equals the
   requested model.
2. Shard ordinals are contiguous from one, paths are unique relative basenames,
   and every name exactly matches the logical stem's numbered-shard grammar.
   Absolute paths, `..`, path separators inside a basename, and symlinks are
   rejected.
3. No matching shard exists outside the index, and no indexed shard is missing.
4. Each compressed and uncompressed hash/count matches while streaming.
5. First/last identities match the first/last decoded records in each shard.
6. The global record count, canonical byte count, canonical hash, identity
   ledger, relation ledger, source universe, and claim universe pass the same
   validation as plain JSONL.

Consumers receive the same ordered model list regardless of plain or sharded
storage. Downstream CLI arguments therefore continue to use, for example,
`--sources output/research/source_records.jsonl` without knowing the physical
layout.

## 6. Atomic publication and ownership

The writer renders the complete new logical ledger into same-directory temporary
files before touching any published name. It validates all new bytes, then
backs up the exact old logical file and every shard matching only that logical
stem.

Publication order is:

1. publish every new numbered shard;
2. publish the new logical index last;
3. remove stale shards owned by that logical stem;
4. remove the transaction backup.

If a rename, index publication, or stale cleanup fails, the writer removes the
affected new-name union and restores the exact previous names and bytes before
re-raising. It never deletes another ledger's shards, archive volumes,
`.github-checkpoints`, external inputs, or unrelated output.

Plain-mode publication uses the same transaction, publishes the canonical
logical JSONL, and removes only stale shards for that same stem after success.

## 7. Coverage and publication manifests

`capture_coverage_manifest.json` gains one artifact descriptor for every
logical JSONL output. The descriptor locks:

- storage mode (`plain` or `canonical-jsonl-gzip-shards-v1`);
- logical path hash and byte count;
- record type and record count;
- logical canonical byte count and SHA-256;
- the exact ordered shard descriptor list when sharded.

`verify-source-capture` reconstructs every ledger through `read_jsonl`, compares
the reconstructed records with the frozen source/claim universe, and compares
the physical descriptor byte-for-byte with the locked coverage descriptor.

The staging gate requires the fixed logical paths plus the exact shard union
named by their indexes. It rejects missing shards, unindexed shards, files at or
above 95,000,000 bytes, checkpoint files, external PDFs, `.gitignore`, and
unowned output.

Task 10 staging is extended only for
`docs/Portfolio_Book/output/research/*-part-[0-9][0-9][0-9].jsonl.gz` files that
are referenced by a locked logical index. A glob alone never grants ownership.

## 8. Affected ledgers and downstream compatibility

The mechanism applies automatically to all `canonical_io` ledgers. In the real
capture it is expected to shard at least:

- `source_records.jsonl`;
- `document_claim_inventory.jsonl`;
- `pr_inventory.jsonl`.

`issue_inventory.jsonl` and `ai_trace_inventory.jsonl` remain plain if their
canonical streams stay within the threshold. Later outputs such as
`classified_source_records.jsonl` use the same transparent contract if they
grow past it.

The case/content and rendering plans keep their existing logical CLI paths. Any
new consumer must call `canonical_io.read_jsonl` or the project CLI resolver;
directly treating a logical file as a guaranteed record-per-line flat file is
not supported once its index declares sharded storage.

## 9. Failure handling

- Oversized compressed single-record shard: block with logical path, record
  identity, compressed size, and safe hashes; never omit the record.
- Missing/corrupt/extra/reordered shard: block before returning any trusted
  ledger.
- Invalid model or duplicate identity across shards: use the existing canonical
  validation error.
- Publication failure: restore the complete old logical ledger byte-for-byte.
- Critical privacy scan failure: retain local output, do not stage, and report
  only safe locator/hash/redaction category.

## 10. Test strategy

Tests use `tmp_path` and injectable small thresholds; they do not create
hundreds of megabytes.

1. RED: with an injected small threshold, a fixture expects an index and
   numbered gzip shards; the current writer instead produces one plain JSONL
   file and fails the behavior assertion.
2. Small input remains byte-identical plain canonical JSONL.
3. Large input produces deterministic gzip shards and a canonical index;
   decompressed concatenation equals the exact expected canonical stream.
4. Boundary records are never split and input order is preserved.
5. Missing, extra, corrupt, reordered, path-traversal, wrong-model, wrong-hash,
   wrong-count, nonzero-mtime, and oversized-shard cases fail.
6. Identity and relation validation spans shard boundaries.
7. Plain-to-sharded, sharded-to-plain, and changed-shard-count replacements
   remove only owned stale files.
8. Injected rename, index-publish, and stale-cleanup failures restore exact old
   names and bytes with no temporary or backup residue.
9. Capture coverage and staged-scope tests require the exact indexed shard
   union.
10. Specialized PR/issue/AI inventories and downstream classified ledgers round
   trip through the unchanged logical path API.

After focused and full tests pass, the real safe archives provide the
integration gate: 520,127 claims must reconstruct exactly, every physical file
must be below its limit, and `verify-source-capture` must exit zero.

## 11. Resume procedure

The existing frozen snapshot, 20,656 GitHub checkpoint files, 13 numbered
GitHub volumes, 11 commit-diff volumes, 12 document volumes, and 2 AI-trace
volumes remain local and unchanged during implementation. `capture-snapshot` is
not rerun.

After this design's implementation and independent review, Task 10 resumes the
same `collect-all` command from the unfinalized snapshot. Cached GitHub evidence
is revalidated according to the existing reconciliation contract. Canonical
ledgers publish only after collectors, explicit relations, coverage, shard
validation, and atomic publication all succeed.

## 12. Alternatives and trade-offs

### Chosen: transparent deterministic gzip shards

- Preserves all logical paths and every canonical record.
- Avoids more than a gigabyte of duplicate uncompressed Git blobs.
- Adds index resolution and multi-file publication complexity.

### Rejected: uncompressed numbered JSONL shards

- Simpler to inspect with generic tools.
- Duplicates hundreds of megabytes already represented in safe archives and
  materially increases clone cost.

### Rejected: Git LFS or one compressed blob

- Git LFS is not configured and would add external storage/authentication state.
- One compressed blob has no durable upper bound as the exhaustive ledger grows
  and would recreate the same publication blocker.
