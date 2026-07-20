# Kafka delivery outcome evidence

## Scope

- Baseline commit: `c14a42a5489cfa6d7ffe69be13c4375d177160bd`
- Captured: 2026-07-20
- Environment fingerprint: Linux `amd64`, 8 logical CPUs, OpenJDK `21.0.11`, Gradle `8.5`, Kotlin `1.9.20`.
- Verification ceiling: static/source evidence and focused tests only. Docker Kafka, service boot, five-minute runtime windows, and load/benchmark matrices were not run.

## Before: listener and policy inventory

| Workload | Source topic | Group ID | Current terminal behavior |
| -- | -- | -- | -- |
| Calculator normal | `external-api.snapshot.chunk-ready` | `calculator-snapshot-chunk-processor` | Local ACK after coordinator; local 3-retry loop plus legacy container retry/DLT |
| Calculator urgent | `external-api.urgent.snapshot.chunk-ready` | `calculator-urgent-chunk-processor` | Same local retry/ACK path as normal |
| Synchronizer basic | `external-api.snapshot.chunk-ready` | `synchronizer-basic-chunk-consumer` | Workload/template ACK |
| Synchronizer urgent basic | `external-api.urgent.snapshot.chunk-ready` | `synchronizer-urgent-basic-chunk-consumer` | Workload/template ACK |
| Synchronizer result | `calculator.result.chunk-ready` | `synchronizer-result-chunk-consumer` | Template ACK; consumed-event publication is an observed-success callback |
| Synchronizer OCID | `external-api.ocid.lookup-ready` | `synchronizer-ocid-lookup-consumer` | ACK in completion callback even when processing fails |
| External urgent | `urgent-character-request` | `external-api-urgent-processor` | ACK on capacity exhaustion and async completion |
| External auth | `auth-character-fetch-request` | `module-external-api-auth-consumer` | ACK after scheduling response work, not after producer completion |
| Cleanup inbox | `synchronizer.chunk.consumed` | `cleanup-inbox` | ACK after enqueue into a bounded in-memory queue |

Static policy evidence:

- Legacy `KafkaConsumerConfig` uses `AckMode.MANUAL`, `DeadLetterPublishingRecoverer`, and `FixedBackOff(1000, 3)`.
- Calculator additionally configures `consumer-max-retries=3` and `consumer-retry-backoff-ms=500`; one record can therefore reach the coordinator up to sixteen times before the legacy DLT path.
- Workload production source contains direct `Acknowledgment`/ACK access in all listed paths.
- Cleanup is not durable: `ConcurrentLinkedQueue`, local counters, and oldest-drop capacity handling own the pending state.

## Reproducible runtime protocol

The approved runtime protocol remains defined for a future controlled measurement:

1. Use local Docker Kafka and the four unchanged service JARs with unchanged partitions/concurrency; record exact JVM options, commit, and CPU fingerprint.
2. Generate UTF-8 keyed corpora for `external-api.snapshot.chunk-ready`: 100 warmup plus 1,000 measured records using `delivery-probe-{warmup|measured}-%05d|{"probe":"kafka-delivery","sequence":N}`.
3. Record SHA-256, byte count, and record count; replay the exact retained bytes after migration.
4. Warm for 60 seconds, then measure for five minutes at 30-second intervals using calculator/synchronizer Prometheus endpoints and normal consumer-group descriptions.
5. Compare counter deltas for consumed/success/retry/pause/DLT/duplicates, source/DLT offsets and lag, CPU/heap, and executor/pool pressure.
6. Characterize the auth DLT with one separately malformed credential-free JSON record; never print or store auth source bodies.

No corpus was generated and no runtime counters, offsets, lags, payloads, or broker metadata were collected in this focused execution. Consequently there is no numeric before/after throughput claim.

## After: implementation evidence

To be filled from focused task tests and source guards. Runtime metrics remain explicitly unmeasured unless a later authorized runtime session follows the exact protocol above.
