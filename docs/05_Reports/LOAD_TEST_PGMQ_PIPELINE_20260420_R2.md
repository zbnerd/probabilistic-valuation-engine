# Load Test Report #2 — PGMQ Pipeline (2026-04-20)

## Heap Size 비교 (2GB vs 8GB vs 16GB)

| 항목 | 2GB | **8GB** | 16GB |
|------|-----|---------|------|
| API Throughput | 75.2 req/s | **123.7 req/s** | 133.1 req/s |
| Request phase | 133.0s | **80.8s** | 75.1s |
| p50 응답시간 | 449ms | **383ms** | 362ms |
| p95 응답시간 | 1,988ms | **749ms** | 606ms |
| p99 응답시간 | 3,641ms | **1,092ms** | 778ms |
| Max 응답시간 | 5,258ms | **1,465ms** | 1,128ms |
| 워커 드레인 평균 | 7.3/s | **12.2/s** | 15.4/s |
| 워커 드레인 최대 | 24.0/s | **22.3/s** | 30.6/s |
| 180s 드레인 처리량 | 2,435건 | **3,811건** | 5,557건 |
| View 기록 | 0건 | **3,811건** | 5,557건 |
| Major GC | 1,193회/391s | **38회/22.1s** | 15회/10.4s |
| Old Gen 사용률 | 76% | **90%** | 48% |
| BulkheadFull | 66,928 | **75,678** | 63,476 |
| HikariCP pending 최대 | 121 | **60** | 55 |

### 결론: **8GB 채택**
- Major GC 38회로 이미 충분 (2GB 대비 97% 감소)
- 16GB 대비 성능 차이의 주원인은 GC가 아닌 Bulkhead
- 8GB로 GC 문제 해결 확인, 다음 병목(Bulkhead) 튜닝으로 전환

---

## 1. 요청 페이즈 (API Acceptance) — 16GB

| 항목 | 값 |
|------|-----|
| Total requests | 10,000 |
| Request phase | 75.1s |
| Throughput | **133.1 req/s** |
| Status 202 (QUEUE) | 10,000 |
| Errors | 0 |

### 응답시간 분포
| percentile | 시간 |
|-----------|------|
| Min | 72ms |
| p50 | 362ms |
| p95 | 606ms |
| p99 | 778ms |
| Max | 1,128ms |

---

## 2. 워커 처리 성능 — 16GB

| 항목 | 값 |
|------|-----|
| 180초 드레인 처리량 | 5,557건 |
| 드레인 속도 평균 | **15.4 items/s** |
| 드레인 속도 최대 | **30.6 items/s** |
| 큐 잔여 | 4,443건 |
| View 기록 | 5,557건 (archive와 1:1 매치) |

---

## 3. GC 개선 — 16GB vs 2GB

### GC 타입별 통계
| GC 타입 | 2GB Heap | **16GB Heap** | 변화 |
|---------|----------|---------------|------|
| Young GC | 9,354회 / 34.3s | 715회 / **19.7s** | -43% |
| Major GC | 1,193회 / 391s | **15회 / 10.4s** | **-97%** |
| Major GC max | 652ms | **879ms** | 유사 |
| Major GC avg | 327ms | **691ms** | 증가 (빈도 극감으로 영향 미미) |

### Old Gen 시계열 (16GB)
```
  Time | OldGen% | Heap%  | HeapMB | OldMB | Note
  ------+---------+--------+--------+-------+--------
    0s  |   1.1%  |  3.3%  |    547 |   188 | 시작
   39s  |   3.1%  |  6.7%  |  1,102 |   504 | 요청 진행
   81s  |  55.3%  | 91.6%  | 15,000 | 9,060 | 피크
  164s  |  19.3%  | 36.4%  |  5,963 | 3,163 | Major GC 해소
  290s  |  80.8%  | 85.2%  | 13,964 | 13,240 | 재상승
  373s  |  87.7%  | 85.2%  | 13,955 | 14,375 | 최종
```

### 최종 메모리 상태 (16GB)
| 영역 | Used | Max | 사용률 |
|------|------|-----|--------|
| Heap | 13,955MB | 16,384MB | 85.2% |
| G1 Old Gen | 14,375MB | 16,384MB | 87.8% |
| G1 Eden | 448MB | - | - |

---

## 4. HikariCP 커넥션 풀

| 항목 | 2GB | **16GB** | 변화 |
|------|-----|----------|------|
| Pending 최대 | 121 | **55** | **-55%** |
| Pending 평균 | 7.5 | **1.0** | **-87%** |
| Active 평균 | 16 | **9.3** | -42% |
| Active 최대 | 30 | 30 | 동일 |

---

## 5. Exception 통계

### 발생 횟수 비교
| Exception | 2GB | **16GB** | 변화 |
|-----------|-----|----------|------|
| BulkheadFullException | 66,928 | **63,476** | -5% |
| LoggingPolicy (task failed) | 35,724 | **45,537** | +28%* |
| ResilientNexonApiClient | 7,516 | **9,437** | +26%* |
| SqlExceptionHelper (DB) | 2,069 | **3,502** | +69%* |
| FallbackHandler | 4,633 | **2,241** | -52% |
| NexonFanOutBatchLoader | 2,776 | **1,130** | -59% |
| ViewUpsert 실패 | 2,395 | **0** | ✅ 해결 |

> *워커가 더 많이 돌아서 총 실패 수는 증가. 하지만 FanOut/Bulkhead per-request 영향은 감소.

### 태스크별 처리시간 — 16GB
| 태스크 | n | avg | p50 | p95 | p99 | max |
|--------|---|-----|-----|-----|-----|-----|
| CalculateOnly | 19,938 | 10.5s | 7.3s | 28.6s | 28.8s | 30.1s |
| CalculateWriteOnly | 19,994 | 10.5s | 7.3s | 28.6s | 28.7s | 30.0s |
| FanOutBatchLoader | 1,152 | 663ms | 635ms | 906ms | 1,017ms | 1.6s |
| AdvisoryLock | 139 | 655ms | 588ms | 1,399ms | 1,411ms | 1.4s |
| CubeService:CalculateDP | 589 | 1ms | 0ms | 5ms | 20ms | 51ms |
| ViewUpsert | 0 failures | - | - | - | - | - |

> CalculateOnly p95=28.6s는 BulkheadFullException 타임아웃 (30s) 근접.
> 실제 계산은 정상이지만 API 호출 대기 시간이 대부분.

---

## 6. View Table (character_valuation_views)

| 항목 | 2GB | **16GB** |
|------|-----|----------|
| 기록 건수 | 0 (JSR310 오류) | **5,557건** ✅ |
| Archive 대비 | - | **100% 매치** |

---

## 7. 8GB Heap 상세 결과

### 요청 페이즈
| 항목 | 값 |
|------|-----|
| Request phase | 80.8s |
| Throughput | 123.7 req/s |
| p50 | 383ms |
| p95 | 749ms |
| p99 | 1,092ms |
| Max | 1,465ms |

### GC
| GC 타입 | 횟수 | 총 시간 | 최대 |
|---------|------|---------|------|
| Young GC | 1,583 | 23.2s | 97ms |
| Major GC | 38 | 22.1s | 921ms |

### 최종 메모리
| 영역 | Used | Max | 사용률 |
|------|------|-----|--------|
| G1 Old Gen | 7,363MB | 8,192MB | 89.9% |
| G1 Eden | 244MB | - | - |

### 태스크별 처리시간
| 태스크 | n | avg | p50 | p95 | max |
|--------|---|-----|-----|-----|-----|
| CalculateOnly | 20,729 | 10.6s | 7.6s | 28.6s | 31.2s |
| CalculateWriteOnly | 20,754 | 10.6s | 7.6s | 28.6s | 31.2s |
| FanOutBatchLoader | 2,094 | 768ms | 670ms | 1,390ms | 1.6s |
| AdvisoryLock | 182 | 718ms | 612ms | 1,146ms | 1.2s |

---

## 8. 병목 해결 현황

| # | 병목 | 상태 | 비고 |
|---|------|------|------|
| 1 | 파싱 3회 중복 | ✅ 해결 | 1-pass 최적화 |
| 2 | GC Humongous | ✅ 해결 | G1HeapRegionSize=4m + Xmx8g |
| 3 | Heap 부족 | ✅ 해결 | Major GC 1,193→38회 (-97%) |
| 4 | View table 미기록 | ✅ 해결 | JavaTimeModule 등록 |
| 5 | Nexon API Bulkhead | 🔴 **1순위** | 75,678회 실패, 다음 타겟 |
| 6 | HikariCP pending | ✅ 완화 | 121→60 (-50%) |

---

## 9. 다음 단계

### 1순위: Bulkhead 용량 튜닝
```
현재: maxConcurrentCalls=50, maxWaitDuration=500ms
→ 워커가 12/s 처리 중인데 Bulkhead가 50 TPS 제한
→ CalculateOnly p95=28.6s (거의 타임아웃)

옵션:
A. maxConcurrentCalls 50→100~200
B. maxWaitDuration 500ms→2000ms
C. 워커 동시성 제한 (pipeline buffer size 조절)
```

### Old Gen 90% (8GB 기준)
```
8GB에서 Old Gen이 90%까지 상승하나 Major GC 38회로 양호.
드레인 후 GC로 회복되므로 운영에 지장 없음.
Bulkhead 해결 후 동시 처리 감소 → 자연 완화 예상.
```
