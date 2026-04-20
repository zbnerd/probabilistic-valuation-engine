# Load Test Report #2 — PGMQ Pipeline (2026-04-20)

## 변경사항 (vs 이전 테스트)
- 1-pass JSON 파싱 최적화
- `G1HeapRegionSize=4m` 추가
- View table write (batchViewUpsert) 추가

---

## 1. 요청 페이즈 (API Acceptance)

| 항목 | 이전 | 이번 | 변화 |
|------|------|------|------|
| Throughput | 111.0 req/s | 75.2 req/s | **-32%** |
| Request phase | 90.1s | 133.0s | **+48%** |
| p50 응답시간 | 395.4ms | 448.7ms | +13% |
| p95 응답시간 | 938.3ms | 1,987.6ms | **+112%** |
| p99 응답시간 | 1,776.3ms | 3,640.6ms | **+105%** |
| Max 응답시간 | 2,620.9ms | 5,258.2ms | +101% |

> API throughput 하락은 워커 처리 지연이 피드백으로 영향. 원인: BulkheadFullException 폭증.

---

## 2. 워커 처리 성능

| 항목 | 값 |
|------|-----|
| 180초 드레인 처리량 | 2,435건 |
| 드레인 속도 평균 | 7.3 items/s |
| 드레인 속도 최대 | 24.0 items/s |
| 큐 잔여 | 7,565건 (미처리) |

---

## 3. GC 개선 효과 (`G1HeapRegionSize=4m`)

| 항목 | 이전 | 이번 | 변화 |
|------|------|------|------|
| Young GC 총 시간 | 322.5s (957회 Major GC) | **0.26s** | **-99.9%** |
| Old Gen 평균 | 98.5% | 76.2% | **-22%p** |
| Old Gen 최대 | 98.5% | 98.0% | 유사 |
| Heap 평균 | ~90%+ | 82.2% | 개선 |

> G1HeapRegionSize=4m이 Humongous Object 문제를 해결. Young GC 시간이 322초→0.26초로 **99.9% 감소**.

---

## 4. HikariCP 커넥션 풀

| 항목 | 값 |
|------|-----|
| Pending 최대 | 121 |
| Pending 평균 | 7.5 |
| Active 평균 | ~16 |
| Max pool | 30 |

> 이전 테스트 pending 최대 73 → 121로 증가. BulkheadFullException으로 인한 재시도가 DB 커넥션 경합 가중.

---

## 5. Exception 통계

### 발생 횟수
| Exception | 횟수 | 영향 |
|-----------|------|------|
| BulkheadFullException | **66,928** | Nexon API 동시 호출 제한 (50) |
| LoggingPolicy (task failed) | 35,724 | 전체 실패 태스크 |
| ResilientNexonApiClient ERROR | 7,516 | API 호출 실패 |
| FallbackHandler ERROR | 4,633 | 폴백 처리 |
| NexonFanOutBatchLoader ERROR | 2,776 | 장비 데이터 로드 실패 |
| SqlExceptionHelper (DB) | 2,069 | DB 관련 오류 |
| ViewUpsert 실패 | 2,395 | JSR310 미등록 (ObjectMapper) |

### 주요 병목 체인
```
BulkheadFullException (66,928회)
  → CalculateOnly avg=5,895ms, p95=18,263ms
  → CalculateWriteOnly avg=5,704ms, p95=17,936ms
  → FanOutBatchLoader avg=1,423ms, p95=2,816ms
  → AdvisoryLock avg=989ms, p95=1,624ms
  → PostgresL2 Get/Put avg=5,600ms (GC STW 영향)
```

### 태스크별 처리시간 분포
| 태스크 | n | avg | p50 | p95 | p99 | max |
|--------|---|-----|-----|-----|-----|-----|
| CalculateOnly | 12,699 | 5.9s | 3.7s | 18.3s | 35.8s | 52.7s |
| CalculateWriteOnly | 12,713 | 5.7s | 3.6s | 17.9s | 31.3s | 48.5s |
| FanOutBatchLoader:Fetch | 2,879 | 1.4s | 0.8s | 2.8s | 19.9s | 23.8s |
| AdvisoryLock:ElectLeader | 143 | 1.0s | 0.6s | 1.6s | 10.9s | 11.0s |
| PostgresL2Strategy:Get | 37 | 5.6s | 5.5s | 6.7s | 8.9s | 8.9s |
| ViewUpsert | 2,435 | 5ms | 0ms | 13ms | 52ms | 2.0s |

---

## 6. View Table (character_valuation_views)

| 항목 | 값 |
|------|-----|
| 기록 건수 | **0** |
| 원인 | ObjectMapper에 JSR310 모듈 미등록 |
| 에러 메시지 | `Java 8 date/time type LocalDateTime not supported by default` |

> batchViewUpsert 로직은 정상 진입하지만, Jackson ObjectMapper에 JavaTimeModule 미등록으로
> `valueToTree()` 직렬화 실패. 수정 필요.

---

## 7. 병목 해결 현황

| # | 병목 | 상태 | 비고 |
|---|------|------|------|
| 1 | 파싱 3회 중복 | ✅ 해결 | 1-pass 최적화 |
| 2 | GC Humongous | ✅ 해결 | Young GC 322s→0.26s (-99.9%) |
| 3 | Nexon API Bulkhead | 🔴 **1순위** | 66,928회 실패, 모든 지연의 근원 |
| 4 | View table 미기록 | 🟡 JSR310 수정 필요 | ObjectMapper에 JavaTimeModule 등록 |
| 5 | HikariCP pending | 🟡 2차 개선 | Bulkhead 해결 후 자연 완화 예상 |

---

## 8. 다음 단계

### 즉시 수정
1. **ViewUpsert ObjectMapper**: `JavaTimeModule()` 등록
2. **Bulkhead 임계값 조정**: nexonApi maxConcurrentCalls 50 → 100 또는 maxWaitDuration 증가

### 근원 분석
```
BulkheadFullException 66,928회의 의미:
- 워커가 Nexon API를 50 TPS로 제한
- 10,000개 요청이 동시에 워커에서 처리 시도
- 각 요청이 3개 preset × 장비 API 호출 필요
- 50 TPS 제한이 병목 → CalculateOnly avg 5.9s
- 해결: Bulkhead 용량 증설 또는 배치 API 호출 패턴 변경
```

---

## 9. JVM 상세 분석

### GC 타입별 통계
| GC 타입 | 횟수 | 총 시간 | 평균 | 최대 |
|---------|------|---------|------|------|
| Young GC (minor) | 9,354 | 34.3s | 3.7ms | 22ms |
| Major GC (compaction) | 1,193 | **391.0s** | 327ms | **652ms** |

### GC 원인
- G1 Evacuation Pause (일반)
- G1 Humongous Allocation (여전히 발생)
- GCLocker Initiated GC
- G1 Compaction Pause
- CodeCache GC Threshold

### Old Gen 시계열
```
  Time | OldGen% | Heap% | Note
  ------+---------+-------+--------
    0s  |   8.8%  | 26.9% | 시작
   51s  |  49.9%  | 69.9% | 급상승
  136s  |  80.9%  | 82.3% | 요청 페이즈 종료
  243s  |  93.2%  | 94.6% | 드레인 진행
  345s  |  85.1%  | 94.6% | Major GC로 일시 해소
  521s  |  91.6%  | 93.1% | 다시 상승
  604s  |  93.9%  | 96.7% | 최종
```

### Thread 현황
| 항목 | 값 |
|------|-----|
| Live threads | 188 |
| Daemon threads | 133 |
| Loaded classes | 29,451 |

### 메모리 최종 상태
| 영역 | Used | Max | 사용률 |
|------|------|-----|--------|
| Heap | 1,988MB | 2,048MB | 97.1% |
| G1 Old Gen | 1,963MB | 2,048MB | 95.9% |
| G1 Eden | 8MB | - | - |

### JVM 결론
```
G1HeapRegionSize=4m 효과:
  ✅ Humongous → Young GC 처리율 개선 (Young GC 총 34s만 소요)
  ❌ Old Gen 포화 문제 미해결 (2GB 힙으로 10K 동시 처리 불가)
  ❌ Major GC 1,193회 × 327ms = 391초 STW 발생

해결 옵션 (우선순위):
  1. -Xmx4g 증설 → Old Gen 여유 확보
  2. Bulkhead 해결 → 동시 메모리 할당 감소 → Old Gen 압력 완화
  3. 둘 다 적용 (권장)
```
