# 07. Parquet+ZSTD PoC Benchmark (issue 1423/1424)

> Side-by-side Parquet+ZSTD vs gzip+JSONL measurement on OCID mapping schema. Numbers only, no recommendation migrated to production.

**영향(Impact):** 평가 결과 3.7× size win vs 63× write slowdown → 현 규모(~600K OCID record/day)에서 format migration 정당화 안 됨. PoC 만 완료, 마이그레이션 보류.

---

## Test Setup
- 10,000 OCID mapping records (schema: userIgn string + ocid nullable string)
- JVM warm (single run, no averages)
- module-external-api PoC harness (`ParquetBenchmark`)

## Results

| Format | Compressed bytes | Write ms | Read ms | Write records/s |
| -- | --: | --: | --: | --: |
| gzip + JSONL | 49,887 | 67 | 50 | 149,253 |
| Parquet + ZSTD | 13,501 | 4,213 | 898 | 2,373 |

## Analysis

- **Size:** Parquet+ZSTD 3.7× smaller (columnar + ZSTD dictionary beats gzip on string columns).
- **Write throughput:** gzip 63× faster (Avro schema build + ZSTD compression per row group is expensive).
- **Read throughput:** gzip 18× faster (sub-second on 10K; absolute numbers tiny).

## Recommendation: DO NOT MIGRATE

3.7× size win does not justify 63× write slowdown at current scale.

## Revisit Trigger

- Iceberg compaction requires columnar format (ADR-735 Phase 3)
- Cross-artifact analytics / SQL-on-lakehouse becomes user-facing
- Native Parquet ingestion tools (Spark/Trino) make read throughput gap irrelevant

## Caveats

- Single-run JVM warm — production-scale (millions of rows) numbers will differ
- No row-group tuning, no page-level ZSTD level override
- parquet-mr 1.17.1 with default ZSTD codec