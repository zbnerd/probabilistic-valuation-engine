# Artifact Format Evolution: Schema Formalization → Parquet PoC → Small-File → Iceberg Readiness

> **For agentic workers:** This spec covers work tracked in GitHub issues 1423, 1424, 1425, 1426, 1427. Issues 1423 and 1424 are duplicates (same Parquet+ZSTD PoC) and will be merged at execution time; both close together.

**Goal:** Make artifact schemas explicit and versioned, prove out Parquet+ZSTD as a future format on the smallest artifact, investigate the small-file problem, and produce an Iceberg readiness assessment — without committing to format migration or Iceberg adoption yet.

**Architecture:** Each issue is an independent sub-project with a single PR. PR ordering is fixed by dependency, not by issue number. ADR-735 (Analytics Platform) remains the parent decision; this spec adds **format substrate readiness** without triggering Iceberg adoption (which remains Phase 3, gated on T7/T8).

**Tech Stack:** Apache Avro 1.11.x, Apache Parquet 1.17.1 (`parquet-avro`), ZSTD level 5, Kotlin 2.x, existing `module-external-api` / `module-common` Gradle modules.

---

## 1. Background

Today's artifact format is implicit gzip-compressed JSONL. Schemas live in Kotlin data classes (`SnapshotChunkRecord`, `CalculationResult`, implicit `{userIgn, ocid}` in `OcidLookupPhase.kt:347`). Adding/renaming a field requires coordinated producer + reader redeploy with no schema registry, no version field, no forward/backward compatibility story.

Operational reality:
- 297 GB/day compressed snapshot, 17 GB/day result, ~120K snapshot files/day, ~144K total files/day
- ~3.45 TB/day uncompressed source data
- Per-day replay must enumerate ~144K MinIO keys; manifest reads return full key list

Forward-looking drivers:
- ADR-735 Phase 3 (Iceberg) requires explicit schema + Parquet substrate
- Schema evolution today is implicit and brittle
- Small-file problem (120K/day) compounds with any future columnar/vectorized reader

## 2. Decisions (per sub-issue)

### 2.1 Issue 1425 — Schema formalization (FIRST, blocks PoC)

**Decision:** Adopt **Apache Avro** as the schema language for all three artifacts (snapshot, result, ocid-mapping). Each artifact gets an `.avsc` file in `module-common/src/main/avro/`. Each artifact record carries `schema_version: int` field. Field IDs are explicit and durable (assigned by hand in the `.avsc`, never re-used on rename).

Why Avro over Protobuf/Parquet-schema:
- Iceberg substrate (`parquet-avro`) reads Avro-format files; Avro is the natural schema language for Parquet in this stack
- Avro supports forward/backward/reserved-name compatibility modes at the schema level (Protobuf requires field-number discipline separately)
- Protobuf would require a separate ID-assignment discipline; Avro field names serve as IDs
- Plain Parquet schema (`.parquet` only) is opaque to humans; Avro `.avsc` is greppable

What this PR ships:
- `module-common/src/main/avro/snapshot.avsc` covering Success / Failure / PreSerialized / CloseSignal variants with `run_id`, `schema_version` fields
- `module-common/src/main/avro/result.avsc` matching `CalculationResult` field-by-field, including `potentialOptions: ["null", "string"]` array (single nullable-element representation)
- `module-common/src/main/avro/ocid-mapping.avsc` with `{userIgn: string, ocid: string, schema_version: int}`
- `module-common` Gradle: add Avro plugin (id `"com.github.davidmc24.gradle.plugin.avro"`) + `parquet-avro` dependency (used by PoC in 1423)
- **No producer/reader rewrite** in this PR. The `.avsc` files are the source of truth for the next PR (1423) to validate against. **No migration of existing JSONL artifacts.**

### 2.2 Issue 1423/1424 — Parquet+ZSTD PoC on OCID mapping (SECOND, uses 1425)

**Decision:** Implement `ParquetOcidMappingWriter` and `ParquetOcidMappingReader` writing side-by-side to `ocid-mapping-parquet/ocid-mapping-$runId.parquet` using the 1425 `.avsc`. Gzip JSONL output remains unchanged. No Kafka topic change; no downstream consumer change.

Why OCID mapping first:
- Smallest artifact (2 fields, ~600K records/run)
- Single producer (`OcidLookupPhase.kt:114-181`), single consumer (`OcidLookupPhase.readCharacterNamesFromChunks`)
- Lowest migration risk surface
- Same pattern will scale to snapshot/result artifacts later

What this PR ships:
- `ParquetOcidMappingWriter` using `ParquetWriter.builder` with `CompressionCodecName.ZSTD` (level 5)
- `ParquetOcidMappingReader` using `AvroParquetReader`
- Trigger from `OcidLookupPhase.execute` as a side-by-side write — **never** replaces JSONL output until benchmark is reviewed
- Benchmark harness: read both gzip and Parquet outputs of the same iteration, capture file size, compression ratio, write records/s, read records/s, write CPU%
- **Decision gate:** write-up of measured numbers + recommendation ("keep JSONL", "migrate OCID mapping", "migrate all artifacts"). No production migration in this PR.

Why ZSTD level 5: balance of compression speed vs ratio. Levels 1-3 are faster but worse ratio; levels 7+ are marginally better ratio with much higher CPU. ADR-735 measured gzip ~3x ratio at level 6; ZSTD L5 typically achieves ~3.5-4x with comparable CPU.

### 2.3 Issue 1427 — Small-file problem investigation (THIRD, independent)

**Decision:** Investigation-only PR. Produces a measurement report + recommendation. **No write-path changes** in this PR.

What this PR ships:
- Measurement harness: MinIO LIST API latency over 1 day (p50/p95/p99), lifecycle-rule evaluation count, distinct-file count per replay scenario, cold-startup manifest read cost
- Consolidation-point analysis:
  - **At write**: increase `chunkSize` config from 500 → 5000 records (10x file reduction, 10x larger individual file blast radius)
  - **At flush**: roll multiple chunks into one before publishing manifest (write-time merge, Kafka event count unchanged)
  - **At close**: append final partial chunk to a merge task that produces one large file (post-write, async)
  - **Post-write**: nightly compaction job (small-file → big-file via streaming merge)
- Comparison table: 144K small files vs N large files at 128 MB target
- Kafka-event-count trade-off analysis (today: 1 event per chunk → after consolidation: 1 event per merged file)
- Recommendation written as ADR (`ADR-XXX-small-file-resolution`)

**No code change in this PR.** Reads from existing `ChunkFileManager` / `CalculationResultWriter` + MinIO + Airflow run history.

### 2.4 Issue 1426 — Iceberg readiness assessment (LAST, gated on 1425 + 1423 + 1427)

**Decision:** Assessment-only PR. Produces a concrete readiness report mapping the 4 prerequisite items. **No Iceberg code** in this PR.

What this PR ships:
- Table schema finalization (Iceberg `Schema` per artifact with explicit field IDs)
- Partition spec recommendation per artifact:
  - `raw_snapshot`: `days(fetched_at)` + `identity(endpoint)`
  - `calc_result`: `bucket(16, ocid)`
  - `ocid_mapping`: `identity(user_ign)`
- Sort-order recommendation per artifact (read-path optimization)
- Catalog comparison: REST vs Hive Metastore vs AWS Glue vs JDBC (Postgres)
- Recommendation: REST catalog (lowest friction with MinIO + existing Postgres), operational estimate ~1-day setup + 0.5 FTE ongoing
- Compaction strategy: `rewrite_data_files` (Spark) vs Java SDK `RewriteFiles` API; target 128 MB/file; daily Airflow DAG; estimated 120K → ~469 files (~250x reduction)
- Schema evolution risk register
- Cost/benefit estimate
- Result: `docs/superpowers/specs/2026-06-28-iceberg-adoption-design.md` (forward-looking, NOT executed by this spec — Phase 3 per ADR-735)

**No code change in this PR.** Report + ADR only.

## 3. Trade-offs

### Sensitivity
- 1425 schema files: low (small file diff, no runtime impact)
- 1423 PoC: medium (new dep, new writer/reader, side-by-side output doubles OCID mapping MinIO storage for that key)
- 1427 investigation: low (read-only)
- 1426 assessment: low (doc-only)

### Trade-off table
| Choice | Gain | Lose |
|---|---|---|
| Avro over Protobuf | Iceberg substrate compat; explicit `.avsc` greppable | Smaller Protobuf payload (not relevant at our sizes) |
| PoC on OCID mapping only | Lowest risk; pattern transferable | Doesn't prove snapshot/result performance |
| Side-by-side Parquet write | No production risk during benchmark | ~2x OCID mapping storage for benchmark period |
| Investigation PRs (1427/1426) have no code | Disciplined ADR before any code | Slower path to actual fix |

### Risk
- 1423 PoC dep addition (parquet-avro transitive deps) might conflict with existing libraries — verify in CI early
- ZSTD level 5 CPU usage during OCID mapping write may slow the phase — benchmark will show
- Small-file investigation may reveal consolidation point that conflicts with Kafka event ordering — defer to recommendation, no code

### Non-Risk
- 1425 `.avsc` files have no runtime effect (gradle-avro plugin does NOT auto-generate Kotlin classes — we keep this manual and disciplined)
- Investigation PRs are read-only — can't break production

## 4. Result / Evidence

Per-PR verification:
- **1425**: `./gradlew :module-common:compileKotlin` passes; `.avsc` files validate against Avro 1.11 schema spec (built-in validation via Avro tools)
- **1423**: writer/reader unit tests pass with both formats producing identical `userIgn → ocid` mappings; benchmark harness produces numeric output table
- **1427**: measurement report committed; recommendation ADR proposed
- **1426**: Iceberg readiness doc committed with all 4 prerequisite items addressed

Cross-cutting evidence: ADR-735 Phase 3 trigger conditions T7/T8 unchanged.

## 5. Execution Order

1. **1425** (schema files, no migration) → PR #A → merge → close 1425
2. **1423** (Parquet PoC, uses 1425 schema) → PR #B → merge → close 1423 + 1424 (dup)
3. **1427** (small-file investigation, independent) → PR #C → merge → close 1427
4. **1426** (Iceberg readiness, gated) → PR #D → merge → close 1426

Each PR is shippable independently. No issue is blocked waiting for review of another.

## 6. Summary

> **Add explicit Avro schemas first; prove Parquet+ZSTD on OCID mapping as side-by-side PoC; investigate small-file problem with measurements; produce Iceberg readiness report — all without committing to format migration or Iceberg adoption, which remain gated on ADR-735 Phase 3 trigger conditions.**