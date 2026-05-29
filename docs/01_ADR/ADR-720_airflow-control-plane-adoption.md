# ADR-720: Airflow Control Plane Adoption — ADR-718 Supersede

- Status: Proposed
- Date: 2026-05-29
- Owner: zbnerd

---

## 1. Background / Problem

### Background

ADR-718에서 Airflow 도입을 기각함. 당시 판단: "파이프라인은 실시간 이벤트 스트리밍, batch ETL 오케스트레이터와 패러다임 다름".

이후 운영 환경 변화:
- 3AM cron 기반 배치 파이프라인의 상태 추적 부재 체감
- 장애 시 수동 복구 반복
- 20+개 스케줄러가 4개 모듈에 분산, 중앙 관리 불가
- external-api / calculator 수평 확장 계획으로 distributed scheduler duplication risk 현실화

### Problem

운영 가시성과 스케줄러 중앙 관리가 1인 운영의 병목.

### Goal

Airflow를 Control Plane으로 도입. Data Plane(Kafka)은 변경 없음.

---

## 2. Decision

> Airflow를 Control Plane으로 도입. Kafka 이벤트 드리븐 Data Plane은 그대로 유지. Airflow는 트리거, 상태 폴링, 알림, 이력 관리만 수행.

```text
Airflow (Control Plane): 트리거, 상태 폴링, SLA, 알림, 런 이력
Kafka (Data Plane): 청크 이벤트 라우팅, 실시간 처리, 재시도, 백프레셔
Services (Execution): API 호출, 계산, DB upsert, 파일 IO
```

ADR-718과의 차이: "Airflow vs Kafka" 이분법 → "Control Plane + Data Plane" 분리.

---

## 3. Trade-offs

### Sensitivity

* DAG 복잡도 (endpoint × calculator × materialization 조합)
* 멀티노드 전환 시 스케줄러 중복 실행
* Airflow metadata DB 부하 (현재 규모에선 낮음)
* MinIO 전환 타이밍 (Phase 3)

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| Airflow Control Plane | 런 추적, 중앙 스케줄링, DAG UI, 수동 트리거 | Airflow 운영 오버헤드 (Python, metadata DB) |
| Kafka Data Plane 유지 | 실시간 처리, 청크 단위 재시도, fan-out | Airflow가 data plane 관여 불가 |
| Coolify + Docker Compose | 단순 멀티노드, K8s 복잡도 회피 | K8s 네이티브 기능 (auto-scaling, self-healing) |
| MinIO object storage | 노드 독립성, 클라우드 마이그레이션 경로 | Local filesystem 성능 (latency) |

### Risk

* Airflow 운영 부담 (1인 팀) — Phase 1→2에서 최소 DAG로 시작
* MinIO 전환 중 일시적 파일 접근 불가 — blue-green 전환 필요
* Coolify 미성숙 — Docker Compose fallback 유지

### Non-Risk

* Kafka consumer 안정성 — Airflow 관여 없음
* ChunkConsumerTemplate retry — 기존 상태 머신 유지
* Urgent 파이프라인 — Airflow 관여 없음
* ConsumedChunkCleanupScheduler — Kafka-driven, data plane 유지

---

## 4. Result / Evidence

### Metrics

| Metric | Before | Target |
| ------ | ------ | ------ |
| Run visibility | Log grep | Airflow UI + run-status API |
| Scheduler count | 8+ @Scheduled (분산) | 2 Airflow DAGs + @Scheduled (data plane) |
| Failure detection | Manual | Airflow SLA + run-status polling |
| Multi-node scheduler safety | N/A | Airflow singleton scheduler |
| Backfill capability | Script | Airflow manual trigger |

### Observed Result

* (Phase 1-2 완료 후 업데이트)

---

## 5. Summary

> ADR-718의 "Airflow vs Kafka" 이분법을 "Control Plane + Data Plane"으로 재평가. Airflow는 관측과 스케줄링만, Kafka는 데이터 처리를 담당.
