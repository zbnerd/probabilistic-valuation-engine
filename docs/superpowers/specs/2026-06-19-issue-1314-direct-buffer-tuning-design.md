# Direct Buffer Tuning (Netty Arenas + Kafka Buffer Memory) — Design

- Issue: #1314
- Date: 2026-06-19
- Status: Draft (pending user review)
- Parent: `docs/superpowers/specs/2026-06-19-offheap-streaming-design.md` §4 Phase 5
- Plan: `docs/superpowers/plans/2026-06-19-offheap-streaming.md` Task 5
- Shape: A (config-only, post-baseline tuning)

---

## 1. Goal

Tune Netty direct buffer arena count and Kafka client buffer memory to fit within the 512MB direct memory cap introduced in Phase 1 (#1310). Reduce RSS by 100-200MB beyond the Phase 1 cap alone.

**Constraints:**
- Must NOT raise the 512MB cap — tuning operates within it.
- Per-environment override path required (different core counts).
- Backward-compatible: local dev with default gradle properties must work without env setup.

**Out of scope (deferred):**
- Async S3 client migration (Phase 3).
- Chronicle Map OCID cache (Phase 2).
- Streaming ext-api chunk parser (Phase 4).

---

## 2. Background

Per `diagnose` run on 2026-06-19 (before Phase 1):

| Module | Heap | RSS | Off-heap gap |
|--------|------|-----|--------------|
| ext-api | 410MB | 1311MB | ~900MB (Netty + Kafka) |
| calculator | 414MB | 1316MB | ~900MB (Kafka dominant) |

Phase 1 added `-XX:MaxDirectMemorySize=512m`, bounding the off-heap pool total. Default pool sizes (Netty `numDirectArenas=cores*2`, Kafka `buffer.memory=256MB`) no longer fit; tuning required.

Netty arena count rationale: each arena holds a chunk pool. Default = `2*cores` (16 on 8-core) — thread-affinity cost high. `cores/2` (4 on 8-core) halves arena count, reducing metadata overhead.

Kafka buffer memory rationale: producer/consumer `buffer.memory` defaults to 32MB/256MB. Cap to 64MB each (both producer+consumer) keeps combined Kafka direct footprint under 128MB, well within remaining headroom after Netty.

---

## 3. Architecture

### 3.1 Configuration layer

```
┌─────────────────────────────────────────────────────────────┐
│ Build-time (Gradle)                                          │
│   gradle.properties: netty.numDirectArenas=4 (default)       │
│   Override: -Pnetty.numDirectArenas=8                        │
│   ↓                                                          │
│   module-external-api/build.gradle jvmArgs:                  │
│     providers.gradleProperty("netty.numDirectArenas")        │
│       .orElse("4")                                           │
│       .map { "-Dio.netty.allocator.numDirectArenas=$it" }    │
│       .get()    ← lazy Provider chain, honors -P at runtime  │
└─────────────────────────────────────────────────────────────┘
┌─────────────────────────────────────────────────────────────┐
│ Runtime (YAML)                                                │
│   application.yml: spring.kafka.{producer,consumer}.properties.  │
│     buffer.memory: ${KAFKA_BUFFER_MEMORY:67108864}           │
│   Override: KAFKA_BUFFER_MEMORY=134217728 env var            │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 Module split

| Module | Netty arena | Kafka producer | Kafka consumer |
|--------|-------------|----------------|----------------|
| ext-api | YES (4) | YES (64MB) | (not in hot path) |
| calculator | NO (no Netty dep) | YES (64MB) | YES (64MB) |

Calculator uses `spring-boot-starter-web` (servlet stack) — no Netty dependency, no arena tuning needed.

---

## 4. File Changes

### 4.1 `module-external-api/build.gradle`

Modify existing `tasks.named("bootRun")` block (lines 64-68). Add second JVM arg using `providers.gradleProperty()` for lazy evaluation (correctly honors `-P` override at task-execution time, unlike GString `${}` which can be eagerly evaluated at configuration):

```groovy
tasks.named("bootRun") {
    jvmArgs = [
        "-XX:MaxDirectMemorySize=512m",
        providers.gradleProperty("netty.numDirectArenas")
            .orElse("4")
            .map { "-Dio.netty.allocator.numDirectArenas=$it" }
            .get(),
    ]
}
```

### 4.2 `gradle.properties` (root)

Append:

```properties
# Netty direct buffer arena count (issue #1314). Default 4 (cores/2 on 8-core).
# Override per env: ./gradlew :module-external-api:bootRun -Pnetty.numDirectArenas=8
netty.numDirectArenas=4
```

### 4.3 `module-external-api/src/main/resources/application.yml`

Extend existing `spring.kafka.producer` block (lines 15-19). Add `properties` sub-key:

```yaml
spring:
  kafka:
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
      acks: all
      retries: 3
      properties:
        buffer.memory: ${KAFKA_BUFFER_MEMORY:67108864}  # 64MB, down from default 256MB (issue #1314)
```

### 4.4 `module-calculator/src/main/resources/application.yml`

Extend existing `spring.kafka.consumer` block (lines 13-20) with `properties`. Add minimal `spring.kafka.producer` block (does not exist yet — calculator produces `calculator.result.chunk-ready` events via `KafkaResultEventPublisher`'s `KafkaTemplate<String, String>`, currently auto-configured by Spring Boot):

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    consumer:
      group-id: calculator-snapshot-chunk-processor
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      auto-offset-reset: earliest
      enable-auto-commit: false
      max-poll-records: 50
      max-poll-interval-ms: 10800000
      properties:
        buffer.memory: ${KAFKA_BUFFER_MEMORY:67108864}  # 64MB (issue #1314)
    producer:
      # Minimal block: only override buffer.memory. Spring Boot auto-config
      # keeps StringSerializer (matches KafkaTemplate<String, String>), acks=1,
      # retries=0, etc. — preserving current producer behavior.
      properties:
        buffer.memory: ${KAFKA_BUFFER_MEMORY:67108864}  # 64MB (issue #1314)
    listener:
      ack-mode: manual
      concurrency: 4
      missing-topics-fatal: false
```

Per `yaml-config.md`: merge into existing `spring:` block. No duplicate root key. 2-space indent. env var pattern matches existing `NEXON_HTTP_*` style.

---

## 5. Configuration Surface

| Variable | Default | Override | Effect |
|----------|---------|----------|--------|
| `netty.numDirectArenas` (gradle property) | `4` | `-Pnetty.numDirectArenas=N` at build time | Netty chunk pool count in ext-api |
| `KAFKA_BUFFER_MEMORY` (env var) | `67108864` (64MB) | Shell env at runtime | Kafka producer+consumer buffer pool size |

Both follow `build-conventions.md` externalization rule.

---

## 6. Error Handling

| Failure | Behavior |
|---------|----------|
| Gradle property unset | Falls back to `4` via `findProperty ?: '4'` |
| `KAFKA_BUFFER_MEMORY` unset | Falls back to `67108864` via Spring `${VAR:default}` |
| Invalid arena count (e.g., 0, negative) | Netty throws at startup, bootRun fails. Caught at dev time, not prod traffic. |
| Invalid `KAFKA_BUFFER_MEMORY` (e.g., negative) | Spring Kafka throws `IllegalArgumentException` at bean init. Boot fails clearly. |
| Arena too small for traffic | Throughput regression visible in `external_api_users_fetched_total` rate. Rollback by reverting files. |

---

## 7. Verification

**Build (required for PR):**

```bash
./gradlew :module-external-api:compileKotlin :module-calculator:compileKotlin --continue
./gradlew :module-external-api:bootJar :module-calculator:bootJar
```

Expected: BUILD SUCCESSFUL. No test impact (config-only).

**Post-merge smoke (ops-owned, 1hr pipeline):**

```bash
nproc  # confirm core count
./gradlew :module-external-api:bootRun :module-calculator:bootRun -Pnetty.numDirectArenas=$(($(nproc)/2))
# After 1hr pipeline run:
ps -o rss= -p $(lsof -ti:8081 -sTCP:LISTEN | head -1)  # ext-api RSS, target <500MB
ps -o rss= -p $(lsof -ti:8082 -sTCP:LISTEN | head -1)  # calc RSS, target <500MB
# Throughput check:
rate(external_api_users_fetched_total[1m]) within ±5% of pre-change baseline
# Alert:
grep -E "OutOfMemoryError|Direct buffer" <module>/logs/app.log  # must be empty
```

---

## 8. Critical Files

| File | Change |
|------|--------|
| `module-external-api/build.gradle` | Add Netty arena JVM arg |
| `gradle.properties` | Add `netty.numDirectArenas=4` default |
| `module-external-api/src/main/resources/application.yml` | Add `spring.kafka.producer.properties.buffer.memory` |
| `module-calculator/src/main/resources/application.yml` | Add `spring.kafka.consumer.properties.buffer.memory` + new `spring.kafka.producer` block |

---

## 9. Reused Symbols

- `-XX:MaxDirectMemorySize=512m` — already in both `bootRun` blocks from #1310.
- `${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}` — existing env var pattern.
- `findProperty` / `${...}` interpolation — existing gradle pattern.
- `spring.kafka.{producer,consumer}.properties` — standard Spring Kafka config path (arbitrary Kafka client props passthrough).

---

## 10. Rollback

Revert 4 files in single commit. No data migration. No state to clean. No DB schema change.

```bash
git revert <commit-sha>
```

Or manual:

```bash
git checkout HEAD~1 -- module-external-api/build.gradle \
                        gradle.properties \
                        module-external-api/src/main/resources/application.yml \
                        module-calculator/src/main/resources/application.yml
```

---

## 11. Open Questions

None. All scope decisions resolved in brainstorming Q&A:
- Q1: Kafka scope → both ext-api + calculator
- Q2: Arena count source → gradle property (default 4)
- Q3: Kafka buffer source → YAML env var (default 64MB)
- Q4: Verification level → build only, smoke deferred to ops
