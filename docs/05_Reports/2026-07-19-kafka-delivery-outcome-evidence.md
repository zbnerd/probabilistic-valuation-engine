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

The shared delivery boundary now owns manual-immediate ACK, one initial attempt plus three technical retries, partition-local serial lanes, pause/resume, DLT-before-commit, rebalance fencing, and bounded delivery metrics. Workload code returns `DeliveryOutcome` and has no Kafka acknowledgment access.

| Listener ID | Source topic | Group ID | DLT sanitizer | Success boundary |
| -- | -- | -- | -- | -- |
| `calculator-snapshot-normal` | `external-api.snapshot.chunk-ready` | `calculator-snapshot-chunk-processor` | pass-through | one coordinator attempt and its required publication complete |
| `calculator-snapshot-urgent` | `external-api.urgent.snapshot.chunk-ready` | `calculator-urgent-chunk-processor` | pass-through | one coordinator attempt and its required publication complete |
| `synchronizer-basic` | `external-api.snapshot.chunk-ready` | `synchronizer-basic-chunk-consumer` | pass-through | DB work, consumed-event send, and success CAS complete |
| `synchronizer-urgent-basic` | `external-api.urgent.snapshot.chunk-ready` | `synchronizer-urgent-basic-chunk-consumer` | pass-through | DB work, consumed-event send, and success CAS complete |
| `synchronizer-result` | `calculator.result.chunk-ready` | `synchronizer-result-chunk-consumer` | pass-through | DB work, consumed-event send, and success CAS complete |
| `synchronizer-ocid-lookup` | `external-api.ocid.lookup-ready` | `synchronizer-ocid-lookup-consumer` | pass-through | OCID ingestion completes |
| `external-api-urgent` | `urgent-character-request` | `external-api-urgent-processor` | pass-through | artifact receipt and all required downstream sends complete |
| `external-api-auth-character-fetch` | `auth-character-fetch-request` | `module-external-api-auth-consumer` | credential-eliding diagnostic envelope | response serialization and Kafka send complete |
| `cleanup-inbox` | `synchronizer.chunk.consumed` | `cleanup-inbox` | pass-through | atomic durable inbox `putIfAbsent` completes |

Deterministic evidence:

- Delivery contracts: 10 focused tests passed.
- ACK/retry/DLT, partition lane, rebalance, and executor ownership: 18 focused tests passed.
- Calculator migration: 11 focused tests passed.
- Synchronizer publish-before-success migration: 14 non-container focused tests passed. The repository Testcontainers/TRUNCATE test was not run under the explicit no-container/no-destructive-DB constraint.
- External API and durable cleanup migration: 21 focused tests passed.
- DLT topology evaluation/resources: 11 focused tests passed, including missing-source no-mutation, create/expand-only behavior, authorization failure propagation, concurrent create-race convergence, cached health, and idempotent bounded close.
- The final workload source guard returned zero matches for `@KafkaListener`, `Acknowledgment`, `acknowledge(`, and `nack(` across calculator, synchronizer, external API, and cleanup production sources.
- The legacy infra configuration is an import facade only; the four ETL applications import `PipelineKafkaConsumerConfiguration` directly.

DLT topology convergence is metadata-only and create/expand-only. It describes all source topics first, treats only an individually described unknown DLT as missing, creates with broker-default replication, expands undersized DLTs, tolerates only verified convergence races, and never deletes, shrinks, or reconfigures topics. Health is `OUT_OF_SERVICE` until the first verified result for an application with subscriptions, then cached `UP`/`DOWN`; applications with zero subscriptions report `UP` with `subscriptions=0`.

Docker Kafka provisioning, service boot, malformed-record broker probes, five-minute load windows, and before/after runtime metrics were intentionally not run under the approved focused-verification ceiling. No broker partition counts, throughput, lag, retry, pause, duplicate, DLT-send, JVM, or pool values are inferred. A future authorized runtime session must use the unchanged protocol above before making a numeric comparison.
