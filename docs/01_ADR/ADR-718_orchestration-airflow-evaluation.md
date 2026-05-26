# ADR-718: Orchestration Framework — Airflow 불도입, Kafka 기반 구조 유지

- Status: Accepted
- Date: 2026-05-22
- Owner: zbnerd

---

## 1. Background / Problem

### Background

현재 파이프라인은 Kafka 이벤트 기반 스트리밍 구조:

```
External API → Kafka (chunk-ready) → Calculator → Kafka (result-ready) → Synchronizer → PostgreSQL
```

직접 구현한 orchestration 요소:
- ChunkConsumerTemplate (retry state machine, lease, exponential backoff)
- ManagedLifecycleCoordinator (순차 시작/종료)
- ScheduledTaskLifecycleWrapper (우아한 종료)
- RunCleanupExecutor (throttled cleanup: max runs/bytes/runtime)
- ConsumedChunkCleanupScheduler (Kafka + 가상스레드 파일 삭제)

### Problem

DAG 확장 시 운영 복잡도 증가 우려:
- endpoint 추가 (union, hexa, symbol, auction 등)
- calculator 종류 증가 (기대값, 시세, 랭킹, 스코어링)
- materialization 다변화 (PostgreSQL, Redis, 검색 인덱스)
- 스케일아웃 (calculator replicas: 6, synchronizer replicas: 3)

이로 인해 task dependency, retry, backfill, cleanup, 상태 추적 관리 복잡도 폭발 가능.

### Goal

오케스트레이션 복잡도 관리 방안 수립. Airflow/Prefect/Dagster 도입 여부 결정.

---

## 2. Decision

> Airflow 도입하지 않음. Kafka 기반 이벤트 스트리밍 구조 유지. 가시성과 backfill은 경량 솔루션으로 해결.

현재 파이프라인은 본질적으로 **실시간 이벤트 스트리밍**이며, Airflow가 설계된 **batch ETL 오케스트레이션**과 패러다임이 다름. Kafka가 이미 phase ordering, fan-out, consumer group 기반 병렬 처리를 담당.

---

## 3. Trade-offs

### Sensitivity

* DAG 복잡도 (endpoint × calculator × materialization 조합)
* 스케일아웃 시 consumer replica 수
* Backfill 빈도 및 범위
* 장애 탐지 속도 (현재 로그 기반)

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| Kafka 구조 유지 | 실시간 처리, 낮은 레이턴시, 자연스러운 fan-out | DAG UI, declarative backfill, 중앙 집중 상태 관리 |
| 경량 admin API | backfill, cleanup 제어 | Airflow Webserver 같은 풍부한 UI |
| Grafana 확장 | pipeline phase 시각화 | Airflow DAG graph 시각화 |

### Risk

* DAG 복잡도가 예상보다 빠르게 증가하면 재검토 필요
* 백필 범위가 커지면 스크립트 기반이 한계에 도달 가능
* k8s 마이그레이션 시 운영 툴링 추가 필요

### Non-Risk

* 스케일아웃 — Kafka consumer group + k8s replicas로 자연스럽게 해결. Airflow 불필요
* Retry — ChunkConsumerTemplate이 Airflow retry보다 정교 (lease, state machine, exponential backoff)
* Cleanup — RunCleanupExecutor + ConsumedChunkCleanupScheduler 이미 동작 확인
* Phase ordering — Kafka topic이 이미 event-driven ordering 담당

---

## 4. Result / Evidence

### Metrics

| Metric | Current | Target |
| ------ | ------: | ------ |
| Backfill | 불가 | Admin API + 스크립트 |
| Pipeline 가시성 | 로그 grep | Grafana phase 메트릭 |
| DAG UI | 없음 | Grafana 대시보드 |
| Cleanup reliability | runCatching (무음 실패) | Alerting 추가 |

### Observed Result

* Pipeline test에서 Kafka 이벤트 806개 처리, lag=0, 235개 파일 자동 삭제 확인
* ChunkConsumerTemplate retry state machine 안정 동작 (max 5회, exponential backoff)
* 스케일아웃 없이 단일 머신에서 60만 유저 처리 (character-basic rate=250/s, item-equipment rate=195/s)

### 대체 도구 비교

| 관점 | Airflow | 현재 구조 (Kafka + k8s) |
|------|---------|----------------------|
| 설계 대상 | Batch ETL | 실시간 스트리밍 |
| Phase ordering | DAG dependency | Kafka event flow |
| Retry | Config 기반 | ChunkConsumerTemplate (더 정교) |
| Scale-out | KubernetesExecutor | k8s replicas + Kafka CG |
| Backfill | Declarative | Admin API + 스크립트 |
| 가시성 | DAG UI | Grafana |
| 운영 오버헤드 | 높음 (Python, metadata DB, webserver) | 낮음 (기존 인프라 활용) |

---

## 5. Summary

> 파이프라인이 실시간 이벤트 스트리밍 구조이므로 batch ETL 오케스트레이터(Airflow)보다 Kafka + k8s + Grafana 조합이 자연스러움. 가시성과 backfill은 경량 솔루션으로 해결.
