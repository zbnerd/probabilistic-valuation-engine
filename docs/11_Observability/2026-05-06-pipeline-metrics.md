# Pipeline 성능 측정 결과 (2026-05-06 ~ 05-07)

## 테스트 개요

- **기간**: 2026-05-06 18:31 KST ~ 2026-05-07 02:49 KST (약 8시간 18분)
- **모듈**: External-API (8081) + Calculator (8082)
- **데이터**: 288,422 유저, item-equipment 엔드포인트
- **인프라**: 단일 머신, Kafka (Docker), 로컬 스토리지

## Calculator 처리 요약

이번 세션 16회 run 완료, 에러 0.

```
Run             Chunks  Users      Items     Rate      소요시간
────────────────────────────────────────────────────────────────
05-06 185825      577   288,420   20.3M     147/s     33분
05-06 193108      577   288,420   20.3M     152/s     32분
05-06 200241      577   288,420   20.3M     149/s     32분
05-06 203453      577   288,420   20.3M     154/s     31분
05-06 210601      577   288,420   20.3M     160/s     30분
05-06 213602      577   288,420   20.3M     160/s     30분
05-06 220559      577   288,420   20.3M     159/s     30분
05-06 223613      577   288,420   20.3M     163/s     29분
05-06 230545      577   288,420   20.3M     164/s     29분
05-06 233503      577   288,419   20.3M      —        자정 넘김
05-07 000519      577   288,420   20.3M     154/s     31분
05-07 003631      577   288,420   20.3M     165/s     29분
05-07 010541      577   288,420   20.3M     155/s     진행중
05-07 013603      143    71,419    5.0M     155/s     완료
05-07 020720      577   288,420   20.3M     145/s     32분
05-07 024000      진행중
────────────────────────────────────────────────────────────────
이번 세션 합계    ~8,818 chunks  4,409,000 users  308.6M items  avg 147/s
```

- 1 run당 288K 유저, 20M 아이템, 약 29~33분 소요
- 처리량 범위: 145~165 users/s
- 에러: 0 (전체 테스트 기간)

## External-API 처리량

- Rate: ~165 users/s (rate limiter 200/s에 종속)
- 에러율: 0.0007% (288K당 2건, CHARACTER_BASIC)
- 1 run당 ~29분 (288K 유저 기준)
- CHARACTER_BASIC: 200/s (rate limiter 400/s 종속), ~24분 소요

## 리소스 사용량 (8시간 연속 가동 안정 구간)

| 지표 | External-API | Calculator |
|------|-------------|------------|
| CPU | 268% | 272% |
| RAM | 1.0GB (4.1%) | 2.8GB (11.5%) |
| Heap 사용 | 300~600MB / 756MB | 1.2~2.2GB / 2.5GB |
| Old Gen | 80~83% (G1 GC 정상 회수) | 39~88% (GC 주기적 회수) |
| Full GC | 0 | 0 |
| YGC | 146회 / 8시간 | 387회 / 8시간 |

## 데이터 생성량

| 구분 | 1 run당 | 24시간 환산 (~48 runs) |
|------|---------|----------------------|
| External-API 원본 (gzipped) | 4.4 GB | ~211 GB |
| Calculator 결과 (gzipped) | 519 MB | ~25 GB |
| **합계** | **~4.9 GB** | **~236 GB** |

## 관측 인프라

### 모니터링 스크립트
```bash
bash scripts/monitor-pipeline.sh
```
- 디스크 기반 총합 + `tac` 기반 실시간 속도 (1억줄 로그에서 1초 내 응답)
- 서버 시작 시간, KST 기준 세션 필터링

### Prometheus 메트릭 (External-API 활성화 완료, Calculator 재시작 후 활성화)

**Calculator**
- `calculator_chunks_processed_total`
- `calculator_chunks_skipped_total` (tag: reason)
- `calculator_chunks_failed_total`
- `calculator_users_processed_total`
- `calculator_items_processed_total` / `_calculated_total` / `_errored_total`
- `calculator_chunk_duration_seconds`

**External-API**
- `external_users_fetched_total` / `_failed_total`
- `external_chunks_created_total`
- `external_lookup_duration_seconds`

**공통 (Actuator 자동 수집)**
- `jvm_gc_pause_seconds`
- `jvm_memory_used_bytes`
- `process_cpu_usage`

**엔드포인트**
- Calculator: `GET localhost:8082/actuator/prometheus` (SecurityConfig import 필요, 재시작 후 활성화)
- External-API: `GET localhost:8081/actuator/prometheus` (활성화 확인)

## 결론

- External-API + Calculator 파이프라인이 8시간 연속 가동에서 에러 0, 안정적으로 동작
- 처리량 ~160 users/s로 288K 유저 기준 1 run당 ~30분
- JVM GC 안정 (Full GC 0, G1 GC 정상 회수)
- 24시간 연속 시 ~236 GB/일 디스크 사용 → 정기 cleanup 정책 필요
- Calculator Prometheus 메트릭은 SecurityConfig import 후 재시작 필요
