# StepTrace 부하테스트 리포트

날짜: 2026-05-02
브랜치: `chore/pgmq-only-result-projection`
조건: COUNT=10000, CONCURRENCY=50
StepTrace 임계값: 500ms (기본값)

---

## 1. ExternalApiWorker:PureCalculate (n=408)

평균 1,427ms. CPU 계산 + 결과 저장 파이프라인.

| Step | avg | p50 | p95 | p99 | max | 코드 위치 |
|---|---|---|---|---|---|---|
| loadInput | 50ms | 16ms | 295ms | 779ms | 1,416ms | `ExternalApiWorker.kt:296` — `calculationInputPort.findByJobId(jobId)` |
| **pureCalculate** | **1,054ms** | **1,013ms** | **2,836ms** | **3,292ms** | **3,668ms** | `ExternalApiWorker.kt:298` — `pureCalculationPort.calculate(input)` (CPU-bound 코루틴) |
| serializeResult | 5ms | 0ms | 3ms | 77ms | 932ms | `ExternalApiWorker.kt:310` — `objectMapper.writeValueAsString(calcResult)` |
| gzipResult | 2ms | 0ms | 1ms | 5ms | 944ms | `ExternalApiWorker.kt:314` — `gzipCompress(resultBytes)` |
| hashResult | 4ms | 0ms | 2ms | 15ms | 953ms | `ExternalApiWorker.kt:318` — `sha256Hex(resultBytes)` |
| completeCalculation | 305ms | 118ms | 995ms | 1,198ms | 2,850ms | `ExternalApiWorker.kt:320` — `executionService.completeCalculation()` (TX: CAS 전환 + result save + PGMQ send) |

**병목**: `pureCalculate` — 순수 CPU 연산 (확률 계산). 캐릭터별 편차 큼 (p50 1s, max 3.7s).

---

## 2. ExternalApiWorker:ProcessMessage (n=8,798)

평균 797ms. 전체 파이프라인 (API 호출 → 스냅샷 → 계산).

| Step | avg | p50 | p95 | p99 | max | 코드 위치 |
|---|---|---|---|---|---|---|
| findJob | 20ms | 12ms | 35ms | 181ms | 1,002ms | `ExternalApiWorker.kt:150` — `jobPort.findJobById(jobId)` |
| **resolveAndFetch** | **431ms** | **378ms** | **714ms** | **1,390ms** | **2,443ms** | `ExternalApiWorker.kt:184` — `resolveOcidAndFetchEquipment()` (OCID resolve + Nexon API 호출) |
| serializeSnapshot | 8ms | 7ms | 17ms | 29ms | 949ms | `ExternalApiWorker.kt:190` — `objectMapper.writeValueAsBytes(equipmentResponse)` |
| buildAndSaveInput | 59ms | 27ms | 202ms | 543ms | 1,687ms | `ExternalApiWorker.kt:221` — `convertItems()` + `calculationInputPort.saveIfAbsent()` |
| awaitSnapshotPut | 0ms | 0ms | 0ms | 4ms | 64ms | `ExternalApiWorker.kt:229` — `snapshotFuture.join()` (비동기 스냅샷 저장 대기) |
| saveSnapshotAndMarkReady | 48ms | 29ms | 92ms | 470ms | 1,482ms | `ExternalApiWorker.kt:251` — `jobService.saveInputSnapshotAndMarkReady()` (TX) |
| runCalculationAndComplete | 229ms | 162ms | 458ms | 1,940ms | 3,711ms | `ExternalApiWorker.kt:255` — `runCalculationAndComplete()` (위 PureCalculate 전체 포함) |

**병목**: `resolveAndFetch` — Nexon 외부 API 응답 시간에 종속. 내부 제어 불가.

---

## 3. ResultProjection:ProjectBatch (n=86)

평균 1,301ms. PGMQ 메시지 읽기 → 뷰 프로젝션.

| Step | avg | p50 | p95 | p99 | max | 코드 위치 |
|---|---|---|---|---|---|---|
| parseMessages | 0ms | 0ms | 0ms | 5ms | 5ms | `ResultReadyProjectionWorker.kt:68` — 메시지 파싱 + jobId 추출 |
| loadCalculationResults | 315ms | 162ms | 1,104ms | 1,605ms | 1,605ms | `ResultReadyProjectionWorker.kt:75` — `jobPort.findJobsByIds()` + `resultPort.findByJobIds()` |
| buildViewRows | 75ms | 28ms | 226ms | 1,009ms | 1,009ms | `ResultReadyProjectionWorker.kt:79` — `buildPgmqProjectionCommands()` (GZIP 해제 + JSON 파싱, 병렬 async) |
| **batchUpsertViews** | **829ms** | **620ms** | **2,124ms** | **3,093ms** | **3,093ms** | `ResultReadyProjectionWorker.kt:85` — `viewQueryPort.batchUpsertFromCalculations()` |
| archiveMessages | 79ms | 29ms | 271ms | 985ms | 985ms | `ResultReadyProjectionWorker.kt:89` — `pgmqClient.archiveBatch()` |

**병목**: `batchUpsertViews` — 내부적으로 `PostgresQuery:BatchUpsertFromCalc` 호출 (아래 참조).

---

## 4. PostgresQuery:BatchUpsertFromCalc (n=49)

평균 1,149ms. JDBC batch `ON CONFLICT` 쓰기.

| Step | avg | p50 | p95 | p99 | max | 코드 위치 |
|---|---|---|---|---|---|---|
| prepareRows | 64ms | 29ms | 174ms | 960ms | 960ms | `CharacterViewQueryServicePostgres.kt:210` — 엔티티 생성 + `MapSqlParameterSource` 구성 |
| **executeValuationViewUpsert** | **713ms** | **586ms** | **1,484ms** | **1,981ms** | **1,981ms** | `CharacterViewQueryServicePostgres.kt:248` — `jdbc.batchUpdate()` (INSERT ... ON CONFLICT DO UPDATE WHERE version <) |
| executeReadModelUpsert | 370ms | 290ms | 1,255ms | 1,415ms | 1,415ms | `CharacterViewQueryServicePostgres.kt:280` — `saveToReadModelBatch()` |

**병목**: `executeValuationViewUpsert` — `ON CONFLICT` 버전 체크 + 쓰기 경합.

---

## 기존 Slow Task 건수 (LoggingPolicy)

| 건수 | Operation |
|---:|---|
| 21,014 | PgmqWorker:ProcessMessage |
| 10,501 | ExternalApiWorker:ProcessMessage |
| 9,994 | ExternalApiWorker:ResolveAndFetch |
| 690 | ExternalApiWorker:PureCalculate |
| 379 | ExternalApiWorker:CompleteCalculation |
| 172 | ResultProjection:ProjectBatch:30 |
| 116 | PostgresQuery:BatchUpsertFromCalculation:30 |

---

## 결론

StepTrace가 "어느 덩어리가 느린지" → "덩어리 안에서 어느 조각이 범인인지" 분리:

| 병목 조각 | 원인 | 최적화 방향 |
|---|---|---|
| `pureCalculate` | CPU 연산 (순수 계산 시간) | 알고리즘 개선 또는 캐릭터별 캐싱 |
| `executeValuationViewUpsert` | JDBC `ON CONFLICT` 쓰기 경합 | batch size 조정, connection pool 튜닝 |
| `resolveAndFetch` | Nexon 외부 API I/O | 외부 제어 불가, 재시도/타임아웃 조정만 가능 |
| `completeCalculation` | TX 커밋 대기 (p99 1.2s) | DB 경합, HikariCP pool sizing |
