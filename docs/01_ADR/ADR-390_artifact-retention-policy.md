# ADR-390: Artifact Retention Policy

- Status: Accepted
- Date: 2026-05-12
- Owner: zbnerd

---

## 1. Background / Problem

### Background

- Three modules (External API, Calculator, Synchronizer) write artifact files to disk continuously
- Kafka stores message logs on the same disk
- No cleanup policy existed — disk filled to 100%, killing Kafka and halting the pipeline
- 129GB of old run data had to be deleted manually

### Problem

- Unbounded disk growth from runs, artifacts, and logs
- No automated lifecycle management
- No visibility into storage usage or cleanup operations

### Goal

- Automated, configurable artifact cleanup with dry-run safety
- Prevent disk-full incidents without manual intervention
- Cleanup must NOT impact main pipeline TPS

---

## 2. Decision

> Retention policy applied uniformly across External API runs and Calculator results. Cleanup is a throttled background job isolated from the processing pipeline.

```text
Keep IF: (active run with _RUNNING marker) OR (recent 5 runs) OR (within 48h)
Delete: only when ALL keep conditions fail
Throttling: max 10 runs/cycle, 5GB/cycle, 60s runtime limit
Dry-run mode: mandatory for first deployment
ocid-lookup/: excluded from cleanup (cache dependency)
Frequency: 6h default interval
```

---

## 3. Trade-offs

### Sensitivity

* Run frequency (hourly vs daily) affects how quickly disk fills
* Cleanup I/O can compete with pipeline disk access
* Kafka log retention vs available disk

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| 48h + recent 5 보존 정책 | 충분한 롤백/조사 창 | 48h 이전 데이터 즉시 삭제 위험 |
| dry-run 기본값 | 삭제 사고 방지 | 첫 배포 시 수동 dry-run→live 전환 필요 |
| 모듈별 독립 스케줄러 | 모듈 독립성, 단순 배포 | 정책 코드 중복 (최소화됨) |
| throttling (10 runs/5GB/60s) | TPS 영향 최소화 | 대규모 정리에 여러 사이클 소요 |
| 6h 주기 | I/O 부하 분산 | 긴급 상황 시 응답 지연 |

### Risk

* _RUNNING marker 미생성 시 활성 run 삭제 가능 (marker 생성 로직으로 완화)
* Calculator는 _RUNNING marker 없음 — 최근 수정 시간(30분)으로 활성 run 추론

### Non-Risk

* ocid-lookup 보존으로 OCID 캐시 재조회 방지
* dry-run 모드로 삭제 전 검증 가능
* runCatching 격리로 cleanup 실패가 pipeline에 영향 없음
* throttling으로 한 cycle에 과도한 I/O 발생 방지

---

## 4. Result / Evidence

### Metrics

| Metric | Target | Notes |
| ------ | ----: | ----- |
| disk usage ratio | < 70% | cleanup 후 |
| cleanup cycle | 6h interval | @Scheduled |
| cleanup duration | < 60s | maxRuntimeSeconds |
| dry-run validation | 24h | 첫 배포 후 전환 |

### Observed Result

* TBD (post-deployment)

---

## 5. Summary

> 48시간 + 최근 5개 run 보존, dry-run 필수, ocid-lookup 제외, 6h 주기 throttled cleanup, pipeline 격리
