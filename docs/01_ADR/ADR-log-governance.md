# ADR-Log-Governance: Log Governance — Structured Logging, PII Masking, ES Pipeline

- Status: Accepted
- Date: 2026-05-13

---

## 1. Background / Problem

### Background

- 3 standalone modules (external-api, calculator, synchronizer) produce plain-text console logs
- No structured logging, no PII masking at source, no cross-module correlation
- Module-synchronizer missing `application` metric tag

### Problem

- Raw IGN/OCID logged in hot paths — PII exposure in log aggregation systems
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

> JSON structured logging via logstash-logback-encoder, Fluent Bit to Elasticsearch pipeline, PII masking in StringMaskingUtils, MDC correlation in Kafka consumers.

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
| logstash-logback-encoder | JSON 구조화, Fluent Bit 자동 파싱 | 로컬 가독성 (local 프로필은 plain text 유지로 완화) |
| Source-level PII masking | 모든 경로에서 PII 보호 | 각 call site 수정 필요 |
| MDC runId/chunkId | 크로스 모듈 파이프라인 추적 | traceId (on-demand 전환 시 추가) |
| ILM 30d delete | 디스크 자동 관리 | 30일 초과 로그 조회 불가 |

### Risk

* Module-app/module-infra PII 유출 36개소는 후속 plan에서 별도 처리

### Non-Risk

* Local 프로필은 plain text console 유지 — 개발 경험 저하 없음

---

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
| ------ | ----: | ----- |
| PII leaks fixed (3 modules) | 1 | calculator SnapshotChunkProcessor OCID |
| JSON log fields | 10+ | service, level, runId, chunkId, kafkaTopic, thread, timestamp, message, stack_trace |
| ILM retention | 30d | 7d rollover, 30d delete |

### Log Budget

* Chunk당 INFO 최대 3줄
* Character-level INFO 금지
* Item-level INFO 금지
* DEBUG는 local/dev only

---

## 5. Summary

> 3개 독립 모듈에 JSON 구조화 로깅 + PII 마스킹 + MDC 상관관계 + Fluent Bit/ES 파이프라인 구축. 메트릭은 Prometheus/Grafana에 유지.
