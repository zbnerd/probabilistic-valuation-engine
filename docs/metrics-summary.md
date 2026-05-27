# Prometheus Metrics Reference

Complete metric catalog for the Probabilistic Valuation Engine pipeline modules.

---

## Calculator (port 8082)

### Counters (Cumulative)

| Metric | Type | Description | Operational Importance |
|--------|------|-------------|----------------------|
| `calculator_users_processed_total` | counter | Total users whose items were calculated | Primary throughput indicator |
| `calculator_items_processed_total` | counter | Total items read from source chunks | Input volume |
| `calculator_items_calculated_total` | counter | Total items with expectation score computed | Actual work output |
| `calculator_items_errored_total` | counter | Items that failed calculation | Error rate — should be 0 |
| `calculator_chunks_processed_total` | counter | Chunks successfully processed | Pipeline progress |
| `calculator_chunks_failed_total` | counter | Chunks that failed processing | **Critical alert** — should be 0 |
| `calculator_chunks_skipped_total` | counter | Chunks skipped (tag: `reason`) | Debug — `endpoint_mismatch`, `result_exists`, `source_not_found` |
| `calculator_result_json_rows_total` | counter | Total JSON rows written to result artifacts | Output volume |

### Gauges (Real-time)

| Metric | Type | Description | Operational Importance |
|--------|------|-------------|----------------------|
| `calculator_chunk_users_per_second` | gauge | Users/s in last completed chunk | Real-time throughput |
| `calculator_chunk_items_per_second` | gauge | Items/s in last completed chunk | Real-time throughput |

**Typical values:** ~450-500 users/s, ~30,000-32,000 items/s

### Histograms

| Metric | Type | Description | Operational Importance |
|--------|------|-------------|----------------------|
| `calculator_chunk_duration_seconds` | histogram | Time to process one chunk | Latency distribution — p99 should be < 2s |

### Byte Metrics

| Metric | Type | Description | Operational Importance |
|--------|------|-------------|----------------------|
| `calculator_input_compressed_bytes_total` | counter | Compressed bytes read from source chunks | Disk I/O read |
| `calculator_input_uncompressed_bytes_total` | counter | Uncompressed bytes read | Data volume processed |
| `calculator_result_compressed_bytes_total` | counter | Compressed bytes written to result chunks | Disk I/O write |
| `calculator_result_uncompressed_bytes_total` | counter | Uncompressed bytes written | Data volume produced |
| `calculator_result_compression_ratio_sum / _count` | histogram | Compression ratio per chunk | Avg ratio = sum/count |

**Compression analysis (82h endurance test):**

| Metric | Compressed | Uncompressed | Ratio |
|--------|----------:|------------:|------:|
| Input | 908 GB | 13.31 TB | 14.7x |
| Output | 94.7 GB | 2.10 TB | 22.2x |

---

## Synchronizer (port 8083)

### Counters

| Metric | Type | Description | Operational Importance |
|--------|------|-------------|----------------------|
| `synchronizer_chunks_received_total` | counter | Kafka messages received | Consumer throughput |
| `synchronizer_chunks_processed_total` | counter | Chunks successfully synced | Progress |
| `synchronizer_chunks_failed_total` | counter | Chunks that failed DB upsert | **Critical alert** — should be 0 |
| `synchronizer_chunks_processing` | gauge | Currently in-flight chunks | Backpressure indicator |
| `synchronizer_documents_processed_total` | counter | Total documents (rows) synced to DB | Data volume |
| `synchronizer_items_processed_total` | counter | Total items within documents | Item-level count |
| `synchronizer_pre_upsert_json_rows_total` | counter | JSON rows before dedup/upsert | Input volume |
| `synchronizer_chunk_status_transition_total` | counter | Status transitions (tag: `status`) | SUCCESS/FAILURE tracking |

### Histograms

| Metric | Description | p50 | p99 | Max |
|--------|-------------|----:|----:|----:|
| `synchronizer_chunk_duration_seconds` | Full chunk sync (read + build + upsert) | ~1.4s | ~2.9s | 1.82s |
| `synchronizer_file_read_duration_seconds` | Gzip file read + parse | ~0.4s | ~0.8s | 0.82s |
| `synchronizer_document_build_duration_seconds` | JSON → DB document mapping | ~0.03s | ~0.1s | 0.09s |
| `synchronizer_main_upsert_duration_seconds` | PostgreSQL batch upsert | ~0.35s | ~1.0s | 7.2s |

### Chunk Detail Metrics

| Metric | Description |
|--------|-------------|
| `synchronizer_chunk_documents_sum / _count` | Average docs per chunk (~1,497) |
| `synchronizer_chunk_bytes_sum / _count` | Average bytes per chunk (~786 KB) |
| `synchronizer_document_equipment_count_sum / _count` | Average equipment items per document (~22) |

---

## External API (port 8081)

### Log-Based Metrics (Prometheus returns 401)

| Metric | Source | Typical Rate |
|--------|--------|-------------|
| Ranking fetch progress | `grep "RankingFetch.*progress"` | 200 pages/s |
| OCID lookup rate | `grep "OCID lookup.*elapsed"` | 400 files/s |
| Character basic rate | `grep "character-basic.*elapsed"` | 250 files/s |
| Item equipment rate | `grep "rate="` | 210-220 files/s |

### Prometheus Counters (if auth disabled)

| Metric | Type | Description |
|--------|------|-------------|
| `external_api_users_fetched_total` | counter | Total users fetched from Nexon API |
| `external_api_users_failed_total` | counter | Total fetch failures |
| `external_api_character_basic_fetched_total` | counter | Character basic endpoint successes |
| `external_api_character_basic_failed_total` | counter | Character basic endpoint failures |
| `external_api_item_equipment_fetched_total` | counter | Item equipment endpoint successes |
| `external_api_item_equipment_failed_total` | counter | Item equipment endpoint failures |
| `external_api_chunks_total` | counter | Total chunks created |
| `external_api_character_basic_duration_seconds` | timer | Character basic phase duration |
| `external_api_item_equipment_duration_seconds` | timer | Item equipment phase duration |

---

## JVM & System Metrics (All Modules)

### Memory

| Metric | Description | Alert |
|--------|-------------|-------|
| `jvm_memory_used_bytes{area="heap"}` | Heap memory usage | > 80% of max |
| `jvm_memory_max_bytes{area="heap"}` | Max heap (-Xmx) | — |
| `jvm_gc_live_data_size_bytes` | Old generation size | Steady growth = leak |

### GC

| Metric | Description | Alert |
|--------|-------------|-------|
| `jvm_gc_pause_seconds` | GC pause duration | p99 > 1s |
| `jvm_gc_memory_promoted_bytes_total` | Promotion to old gen | Sudden spike |

### CPU & Threads

| Metric | Description | Alert |
|--------|-------------|-------|
| `process_cpu_usage` | Process CPU (0-1) | Sustained > 0.8 |
| `jvm_threads_live_threads` | Active thread count | Unexpected growth |

---

## Useful PromQL Queries

```promql
# Calculator throughput (users/s over 5 min)
rate(calculator_users_processed_total[5m])

# Calculator error rate
rate(calculator_items_errored_total[5m])

# Synchronizer lag (chunks received - processed)
synchronizer_chunks_received_total - synchronizer_chunks_processed_total

# Data volume processed (TB)
calculator_input_uncompressed_bytes_total / 1099511627776

# Avg chunk processing time (calculator)
rate(calculator_chunk_duration_seconds_sum[5m]) / rate(calculator_chunk_duration_seconds_count[5m])

# Memory usage percentage
jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"} * 100
```
