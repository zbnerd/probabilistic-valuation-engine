# Elasticsearch Logging Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship structured JSON logs from 3 standalone modules (external-api, calculator, synchronizer) to Elasticsearch via Fluent Bit, with PII masking, cross-module correlation, and index lifecycle management.

**Architecture:** Each module emits JSON logs via logback-logstash-encoder to stdout. Fluent Bit collects container stdout, enriches with metadata, and ships to Elasticsearch. Kafka consumers propagate runId/chunkId via MDC for cross-module correlation. ILM manages retention (30d delete).

**Tech Stack:** logstash-logback-encoder 8.0, Fluent Bit 3.x, Elasticsearch 8.17, Kibana 8.17

---

## File Structure

### New Files
- `docker/fluent-bit/fluent-bit.yml` — Fluent Bit configuration (service, inputs, outputs)
- `docker/fluent-bit/parsers.conf` — JSON parser for structured logs
- `module-external-api/src/main/resources/logback-spring.xml` — JSON structured logging
- `module-calculator/src/main/resources/logback-spring.xml` — JSON structured logging
- `module-synchronizer/src/main/resources/logback-spring.xml` — JSON structured logging
- `scripts/es-setup-ilm.sh` — ES index template + ILM policy bootstrap script

### Modified Files
- `gradle/libs.versions.toml` — Add logstash-logback-encoder version + library
- `module-common/src/main/kotlin/maple/expectation/util/StringMaskingUtils.kt` — Add maskIgn(), hashForCorrelation()
- `module-calculator/src/main/kotlin/maple/calculator/processor/SnapshotChunkProcessor.kt:193` — Fix raw OCID leak
- `module-calculator/build.gradle` — Add logstash-logback-encoder dependency
- `module-external-api/build.gradle` — Add logstash-logback-encoder dependency
- `module-synchronizer/build.gradle` — Add logstash-logback-encoder dependency + application tag
- `module-synchronizer/src/main/resources/application.yml` — Add management.metrics.tags.application
- `module-calculator/src/main/kotlin/maple/calculator/consumer/KafkaSnapshotChunkReadyConsumer.kt` — MDC propagation
- `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/KafkaResultChunkConsumer.kt` — MDC propagation
- `docker-compose.yml` — Add Fluent Bit service

---

## Task 1: Add maskIgn() + logstash-logback-encoder to version catalog

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `module-common/src/main/kotlin/maple/expectation/util/StringMaskingUtils.kt`

- [ ] **Step 1: Add version catalog entries**

Add to `gradle/libs.versions.toml` under `[versions]`:
```toml
logstash-logback = "8.0"
```

Add to `gradle/libs.versions.toml` under `[libraries]`:
```toml
logstash-logback-encoder = { module = "net.logstash.logback:logstash-logback-encoder", version.ref = "logstash-logback" }
```

- [ ] **Step 2: Add maskIgn() and hashForCorrelation() to StringMaskingUtils**

Add to `module-common/src/main/kotlin/maple/expectation/util/StringMaskingUtils.kt` after `maskAccountId()`:

```kotlin
private const val IGN_MASK = "***"
private const val IGN_MIN_LENGTH = 2
private const val IGN_PREFIX_LENGTH = 1
private const val IGN_SUFFIX_LENGTH = 1

/**
 * IGN(캐릭터명) 마스킹: 첫 글자 + "***" + 마지막 글자
 * 예: "진격캐넌" → "진***넌"
 */
@JvmStatic
fun maskIgn(value: String?): String {
    if (value == null || value.length < IGN_MIN_LENGTH) return IGN_MASK
    return value.substring(0, IGN_PREFIX_LENGTH) + IGN_MASK + value.substring(value.length - IGN_SUFFIX_LENGTH)
}

/**
 * Correlation용 SHA-256 해시 (PII 아님, 검색 가능)
 * OCID 등 stable identifier의 교차 모듈 추적에 사용
 */
@JvmStatic
fun hashForCorrelation(value: String?): String {
    if (value == null) return "null"
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(value.toByteArray(Charsets.UTF_8))
    return hash.take(8).joinToString("") { "%02x".format(it) }
}
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :module-common:compileKotlin --continue`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml module-common/src/main/kotlin/maple/expectation/util/StringMaskingUtils.kt
git commit -m "feat: add maskIgn(), hashForCorrelation() to StringMaskingUtils + logstash-logback-encoder catalog entry"
```

---

## Task 2: Fix PII leak in module-calculator SnapshotChunkProcessor

**Files:**
- Modify: `module-calculator/src/main/kotlin/maple/calculator/processor/SnapshotChunkProcessor.kt:193`

- [ ] **Step 1: Apply maskOcid to the raw OCID leak**

Change line 193 in `SnapshotChunkProcessor.kt` from:
```kotlin
log.warn("Calculation error: ocid={} preset={}: {}", flatItem.ocid, flatItem.presetNo, ex.message)
```
to:
```kotlin
log.warn("Calculation error: ocid={} preset={}: {}", StringMaskingUtils.maskOcid(flatItem.ocid), flatItem.presetNo, ex.message)
```

Add import if not present:
```kotlin
import maple.expectation.util.StringMaskingUtils
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :module-calculator:compileKotlin --continue`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add module-calculator/src/main/kotlin/maple/calculator/processor/SnapshotChunkProcessor.kt
git commit -m "fix: mask raw OCID in SnapshotChunkProcessor calculation error log"
```

---

## Task 3: JSON structured logging for module-external-api

**Files:**
- Modify: `module-external-api/build.gradle`
- Create: `module-external-api/src/main/resources/logback-spring.xml`

- [ ] **Step 1: Add logstash-logback-encoder dependency**

Add to `module-external-api/build.gradle` dependencies block:
```groovy
// Structured JSON logging
implementation(libs.logstash.logback.encoder)
```

- [ ] **Step 2: Create logback-spring.xml**

Create `module-external-api/src/main/resources/logback-spring.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <springProperty scope="context" name="APP_NAME" source="spring.application.name" defaultValue="external-api"/>
    <springProperty scope="context" name="SERVER_PORT" source="server.port" defaultValue="8081"/>

    <appender name="JSON_CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <customFields>{
                "service":"${APP_NAME}",
                "port":"${SERVER_PORT}"
            }</customFields>
            <includeMdc>true</includeMdc>
            <includeContext>true</includeContext>
            <fieldNames>
                <timestamp>timestamp</timestamp>
                <version>[ignore]</version>
                <levelValue>[ignore]</levelValue>
            </fieldNames>
        </encoder>
    </appender>

    <springProfile name="local">
        <appender name="PLAIN_CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
            <encoder>
                <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
            </encoder>
        </appender>
        <root level="INFO">
            <appender-ref ref="PLAIN_CONSOLE"/>
        </root>
    </springProfile>

    <springProfile name="!local">
        <root level="INFO">
            <appender-ref ref="JSON_CONSOLE"/>
        </root>
    </springProfile>

    <logger name="maple.externalapi" level="INFO"/>
</configuration>
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :module-external-api:compileKotlin --continue`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add module-external-api/build.gradle module-external-api/src/main/resources/logback-spring.xml
git commit -m "feat: add JSON structured logging to module-external-api via logstash-logback-encoder"
```

---

## Task 4: JSON structured logging for module-calculator

**Files:**
- Modify: `module-calculator/build.gradle`
- Create: `module-calculator/src/main/resources/logback-spring.xml`

- [ ] **Step 1: Add logstash-logback-encoder dependency**

Add to `module-calculator/build.gradle` dependencies block:
```groovy
// Structured JSON logging
implementation(libs.logstash.logback.encoder)
```

- [ ] **Step 2: Create logback-spring.xml**

Create `module-calculator/src/main/resources/logback-spring.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <springProperty scope="context" name="APP_NAME" source="spring.application.name" defaultValue="calculator"/>
    <springProperty scope="context" name="SERVER_PORT" source="server.port" defaultValue="8082"/>

    <appender name="JSON_CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <customFields>{
                "service":"${APP_NAME}",
                "port":"${SERVER_PORT}"
            }</customFields>
            <includeMdc>true</includeMdc>
            <includeContext>true</includeContext>
            <fieldNames>
                <timestamp>timestamp</timestamp>
                <version>[ignore]</version>
                <levelValue>[ignore]</levelValue>
            </fieldNames>
        </encoder>
    </appender>

    <springProfile name="local">
        <appender name="PLAIN_CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
            <encoder>
                <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
            </encoder>
        </appender>
        <root level="INFO">
            <appender-ref ref="PLAIN_CONSOLE"/>
        </root>
    </springProfile>

    <springProfile name="!local">
        <root level="INFO">
            <appender-ref ref="JSON_CONSOLE"/>
        </root>
    </springProfile>

    <logger name="maple.calculator" level="INFO"/>
</configuration>
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :module-calculator:compileKotlin --continue`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add module-calculator/build.gradle module-calculator/src/main/resources/logback-spring.xml
git commit -m "feat: add JSON structured logging to module-calculator via logstash-logback-encoder"
```

---

## Task 5: JSON structured logging for module-synchronizer

**Files:**
- Modify: `module-synchronizer/build.gradle`
- Create: `module-synchronizer/src/main/resources/logback-spring.xml`
- Modify: `module-synchronizer/src/main/resources/application.yml`

- [ ] **Step 1: Add logstash-logback-encoder dependency**

Add to `module-synchronizer/build.gradle` dependencies block:
```groovy
// Structured JSON logging
implementation(libs.logstash.logback.encoder)
```

- [ ] **Step 2: Add missing management.metrics.tags.application**

Add to `module-synchronizer/src/main/resources/application.yml` under `management:`:
```yaml
  metrics:
    tags:
      application: synchronizer
```

- [ ] **Step 3: Create logback-spring.xml**

Create `module-synchronizer/src/main/resources/logback-spring.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <springProperty scope="context" name="APP_NAME" source="spring.application.name" defaultValue="synchronizer"/>
    <springProperty scope="context" name="SERVER_PORT" source="server.port" defaultValue="8083"/>

    <appender name="JSON_CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <customFields>{
                "service":"${APP_NAME}",
                "port":"${SERVER_PORT}"
            }</customFields>
            <includeMdc>true</includeMdc>
            <includeContext>true</includeContext>
            <fieldNames>
                <timestamp>timestamp</timestamp>
                <version>[ignore]</version>
                <levelValue>[ignore]</levelValue>
            </fieldNames>
        </encoder>
    </appender>

    <springProfile name="local">
        <appender name="PLAIN_CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
            <encoder>
                <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
            </encoder>
        </appender>
        <root level="INFO">
            <appender-ref ref="PLAIN_CONSOLE"/>
        </root>
    </springProfile>

    <springProfile name="!local">
        <root level="INFO">
            <appender-ref ref="JSON_CONSOLE"/>
        </root>
    </springProfile>

    <logger name="maple.synchronizer" level="INFO"/>
</configuration>
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew :module-synchronizer:compileKotlin --continue`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add module-synchronizer/build.gradle module-synchronizer/src/main/resources/logback-spring.xml module-synchronizer/src/main/resources/application.yml
git commit -m "feat: add JSON structured logging to module-synchronizer + fix missing application metric tag"
```

---

## Task 6: MDC propagation in Kafka consumers (runId, chunkId)

**Files:**
- Modify: `module-calculator/src/main/kotlin/maple/calculator/consumer/KafkaSnapshotChunkReadyConsumer.kt`
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/KafkaResultChunkConsumer.kt`

- [ ] **Step 1: Add MDC propagation to KafkaSnapshotChunkReadyConsumer**

In the `@KafkaListener` method, add MDC context at the start of processing. Find the point after the event is deserialized (where `SnapshotChunkReadyEvent` is available) and before business logic:

```kotlin
import org.slf4j.MDC

// Inside the consume method, after event deserialization:
MDC.put("runId", event.runId)
MDC.put("chunkId", event.chunkId)
MDC.put("kafkaTopic", "external-api.snapshot.chunk-ready")
try {
    // existing business logic
} finally {
    MDC.clear()
}
```

Wrap the existing business logic in try/finally for MDC cleanup.

- [ ] **Step 2: Add MDC propagation to KafkaResultChunkConsumer**

Same pattern in `KafkaResultChunkConsumer.kt`:

```kotlin
import org.slf4j.MDC

// Inside the consume method, after event deserialization:
MDC.put("runId", event.sourceRunId)
MDC.put("chunkId", event.sourceChunkId)
MDC.put("kafkaTopic", "calculator.result.chunk-ready")
try {
    // existing business logic
} finally {
    MDC.clear()
}
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :module-calculator:compileKotlin :module-synchronizer:compileKotlin --continue`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add module-calculator/src/main/kotlin/maple/calculator/consumer/KafkaSnapshotChunkReadyConsumer.kt module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/KafkaResultChunkConsumer.kt
git commit -m "feat: propagate runId/chunkId/kafkaTopic via MDC in Kafka consumers"
```

---

## Task 7: Fluent Bit configuration

**Files:**
- Create: `docker/fluent-bit/fluent-bit.yml`
- Create: `docker/fluent-bit/parsers.conf`

- [ ] **Step 1: Create Fluent Bit main config**

Create `docker/fluent-bit/fluent-bit.yml`:
```yaml
service:
  log_level: info

input:
  - name: tail
    path: /var/log/containers/*.log
    parser: docker_json
    tag: kube.*
    mem_buf_limit: 5MB
    skip_long_lines: on
    refresh_interval: 5

filter:
  - name: parser
    match: kube.*
    key_name: log
    parser: logstash_json
    reserve_data: on

output:
  - name: es
    match: kube.*
    host: elasticsearch
    port: 9200
    logstash_format: on
    logstash_prefix: logs
    replace_dots: on
    retry_limit: false
    buffer_size: 4KB
```

- [ ] **Step 2: Create Fluent Bit parsers**

Create `docker/fluent-bit/parsers.conf`:
```ini
[PARSER]
    Name   docker_json
    Format json
    Time_Key time
    Time_Format %Y-%m-%dT%H:%M:%S.%LZ

[PARSER]
    Name   logstash_json
    Format json
    Time_Key timestamp
    Time_Format %Y-%m-%dT%H:%M:%S.%LZ
    Json_Key_Under_Key message
```

- [ ] **Step 3: Commit**

```bash
git add docker/fluent-bit/fluent-bit.yml docker/fluent-bit/parsers.conf
git commit -m "feat: add Fluent Bit configuration for structured JSON log shipping to ES"
```

---

## Task 8: ES index template + ILM policy

**Files:**
- Create: `scripts/es-setup-ilm.sh`

- [ ] **Step 1: Create ES bootstrap script**

Create `scripts/es-setup-ilm.sh`:
```bash
#!/bin/bash
# ES Index Template + ILM Policy Bootstrap
# Run once after ES is up: bash scripts/es-setup-ilm.sh

ES_HOST="${ES_HOST:-localhost:9200}"

echo "Creating ILM policy (30-day retention)..."
curl -s -X PUT "${ES_HOST}/_ilm_policy/logs-retention" -H 'Content-Type: application/json' -d '
{
  "policy": {
    "phases": {
      "hot": {
        "min_age": "0ms",
        "actions": {
          "rollover": {
            "max_age": "7d",
            "max_primary_shard_size": "50gb"
          },
          "set_priority": {
            "priority": 100
          }
        }
      },
      "delete": {
        "min_age": "30d",
        "actions": {
          "delete": {}
        }
      }
    }
  }
}
'

echo ""
echo "Creating index template for logs-*..."
curl -s -X PUT "${ES_HOST}/_index_template/logs-template" -H 'Content-Type: application/json' -d '
{
  "index_patterns": ["logs-*"],
  "template": {
    "settings": {
      "number_of_shards": 1,
      "number_of_replicas": 0,
      "index.lifecycle.name": "logs-retention",
      "index.lifecycle.rollover_alias": "logs"
    },
    "mappings": {
      "properties": {
        "timestamp": { "type": "date" },
        "level": { "type": "keyword" },
        "service": { "type": "keyword" },
        "logger_name": { "type": "keyword" },
        "message": { "type": "text" },
        "runId": { "type": "keyword" },
        "chunkId": { "type": "keyword" },
        "kafkaTopic": { "type": "keyword" },
        "thread": { "type": "keyword" },
        "stack_trace": { "type": "text" }
      }
    }
  }
}
'

echo ""
echo "Done. Verify with:"
echo "  curl -s ${ES_HOST}/_ilm/policy/logs-retention | jq"
echo "  curl -s ${ES_HOST}/_index_template/logs-template | jq"
```

- [ ] **Step 2: Make executable**

Run: `chmod +x scripts/es-setup-ilm.sh`

- [ ] **Step 3: Commit**

```bash
git add scripts/es-setup-ilm.sh
git commit -m "feat: add ES index template + ILM policy bootstrap script (30d retention)"
```

---

## Task 9: docker-compose Fluent Bit integration

**Files:**
- Modify: `docker-compose.yml`

- [ ] **Step 1: Add Fluent Bit service to docker-compose.yml**

Add after the Kibana service block in `docker-compose.yml`:

```yaml
  # Fluent Bit (Log Shipper)
  fluent-bit:
    image: fluent/fluent-bit:3.2
    container_name: maple-fluent-bit
    restart: always
    volumes:
      - ./docker/fluent-bit/fluent-bit.yml:/fluent-bit/etc/fluent-bit.yml:ro
      - ./docker/fluent-bit/parsers.conf:/fluent-bit/etc/parsers.conf:ro
      - /var/lib/docker/containers:/var/log/containers:ro
      - /var/run/docker.sock:/var/run/docker.sock:ro
    networks:
      - maple-network
    depends_on:
      - elasticsearch
```

- [ ] **Step 2: Validate docker-compose YAML**

Run: `docker compose config --quiet`
Expected: no error output (warnings about unset env vars are OK)

- [ ] **Step 3: Commit**

```bash
git add docker-compose.yml
git commit -m "feat: add Fluent Bit service to docker-compose for log shipping to ES"
```

---

## Task 10: Log governance ADR

**Files:**
- Create: `docs/01_ADR/ADR-XXX_log-governance.md`

- [ ] **Step 1: Write ADR**

Create `docs/01_ADR/ADR-XXX_log-governance.md`:
```markdown
# ADR-XXX: Log Governance — Structured Logging, PII Masking, ES Pipeline

- Status: Accepted
- Date: 2026-05-13

---

## 1. Background / Problem

### Background

- 3 standalone modules (external-api, calculator, synchronizer) produce plain-text console logs
- No structured logging, no PII masking at source, no cross-module correlation
- Module-synchronizer missing `application` metric tag

### Problem

- Raw IGN/OCID logged in hot paths → PII exposure in log aggregation systems
- Plain-text logs cannot be parsed reliably for search/aggregation
- No runId/chunkId correlation across Kafka pipeline boundary
- ES disk fills without lifecycle management

### Goal

- Structured JSON logs for Elasticsearch ingestion
- PII masking at source level (before any log shipper)
- Cross-module correlation via runId/chunkId in MDC
- Automated retention via ILM

---

## 2. Decision

> JSON structured logging via logstash-logback-encoder, Fluent Bit → Elasticsearch pipeline, PII masking in StringMaskingUtils, MDC correlation in Kafka consumers.

```text
App (JSON stdout) → Fluent Bit → Elasticsearch → Kibana
App (/actuator/prometheus) → Prometheus → Grafana
```

---

## 3. Trade-offs

### Sensitivity

* Log volume (40-50K lines/cycle during load test)
* PII in hot-path workers
* ES disk growth rate

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| logstash-logback-encoder | JSON 구조화, Fluent Bit 자동 파싱 | 로컬 개발 시 가독성 저하 (local 프로필은 plain text 유지로 완화) |
| Source-level PII masking | 모든 경로(stdout, file, docker logs)에서 PII 보호 | 각 call site 수정 필요 |
| MDC runId/chunkId | 크로스 모듈 파이프라인 추적 | traceId (on-demand 전환 시 P2로 추가) |
| ILM 30d delete | 디스크 자동 관리 | 30일 초과 로그 조회 불가 |

### Risk

* Module-app/module-infra의 36개 PII 유출 지점은 후속 plan에서 별도 처리

### Non-Risk

* Local 프로필은 plain text console 유지 → 개발 경험 저하 없음

---

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
| ------ | ----: | ----- |
| PII leaks in 3 standalone modules | 1 | calculator SnapshotChunkProcessor OCID |
| JSON log fields | 10+ | service, level, runId, chunkId, kafkaTopic, thread, timestamp, message, stack_trace |
| ILM retention | 30d | 7d rollover, 30d delete |

### Log Budget

* Chunk당 INFO 최대 3줄
* Character-level INFO 금지
* Item-level INFO 금지
* DEBUG는 local/dev only

---

## 5. Summary

> 3개 독립 모듈에 JSON 구조화 로깅 + PII 마스킹 + MDC 상관관계 + Fluent Bit → ES 파이프라인 구축. 메트릭은 Prometheus/Grafana에 유지.
```

- [ ] **Step 2: Commit**

```bash
git add docs/01_ADR/ADR-XXX_log-governance.md
git commit -m "docs: add log governance ADR for structured logging, PII masking, ES pipeline"
```

---

## Self-Review

### Spec Coverage
- PII masking in 3 standalone modules → Task 1 (utility) + Task 2 (calculator fix)
- JSON structured logging → Tasks 3, 4, 5
- Cross-module correlation → Task 6 (MDC)
- Fluent Bit + ES → Tasks 7, 8, 9
- Documentation → Task 10
- module-synchronizer application tag → Task 5
- Metrics stay in Prometheus → confirmed, no metric→ES tasks
- ILM → Task 8

### Placeholder Scan
- No TBD, TODO, or "implement later" found
- All steps contain specific code or commands

### Type Consistency
- `StringMaskingUtils.maskOcid()` matches existing API (used in EquipmentDbWorker)
- `maskIgn()` follows same pattern as existing `maskOcid()`
- MDC keys (`runId`, `chunkId`, `kafkaTopic`) match ES index template keyword fields
- logback-spring.xml `springProperty` names match YAML keys

### Follow-up Work (separate plan)
- PII masking in module-app + module-infra (36 locations)
- traceId propagation for on-demand request-driven workflow
- Fluent Bit backpressure / buffer tuning
- Kibana dashboards
- Log volume reduction (character/item-level INFO → DEBUG)
