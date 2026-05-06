# ADR: Module-Calculator 탄생 과정 — AI 한계와 인간 설계의 교차점

**Status**: Accepted
**Date**: 2026-05-06
**Context**: module-external-api, module-calculator의 설계 진화 과정 기록

---

## Summary

module-calculator는 하루 만에 구축됐지만, 그 과정은 단순한 "AI가 코드를 생성"한 것이 아니다.
PGMQ 파이프라인 병목을 추적하던 중 계산 단계가 진짜 병목이라는 것을 발견했고,
이를 해결하기 위해 pipeline 아키텍처를 새로 설계하면서 기존 계산 엔진을 재사용하는 방식을 택했다.
이 문서는 그 과정을 커밋 단위로 추적한 것이다.

---

## Phase 1: module-external-api — 독립 모듈로 Nexon API 수집 (5/4)

### 1-1. 스켈레톤 생성 (04:40~05:24)

첫 3개 커밋으로 standalone 모듈 뼈대를 완성했다.

| 시간 | 커밋 | 내용 |
|------|------|------|
| 04:40 | `e188084` | Hexagonal Architecture 스켈레톤. Domain/Port/Adapter, Bucket4j rate limiting(200/s), CSV 리더(300K IGN), mock 테스트 |
| 05:02 | `06b1dae` | 독립 실행 Spring Boot 앱 + scheduled OCID lookup |
| 05:17 | `77a272d` | startup trigger + 런타임 이슈 수정 |
| 05:24 | `395dc56` | scheduler 진행률 로깅 개선 |

핵심 설계 결정: **기존 PGMQ 파이프라인과 완전 분리**. Storage-only 책임. DB 의존성 없음.

### 1-2. 실제 API 연동 + 3단계 파이프라인 (06:34~10:31)

| 시간 | 커밋 | 내용 |
|------|------|------|
| 06:34 | `07d800` | 실제 Nexon API 연결. WebClient + virtual thread fan-out → **200 files/s** (300K OCID를 25분, 기존 5.5시간 대비 13x) |
| 06:56 | `a4ce1f` | CHARACTER_BASIC 파이프라인 + 2-char 샤딩 디렉토리 구조 |
| 06:58 | `29d482` | OCID 완료 시 skip 최적화 |
| 07:42 | `a4e250` | OCID/BASIC rate limit 분리 (OCID 400/s, BASIC 200/s) |
| 08:02 | `6056c3` | ITEM_EQUIPMENT 파이프라인 + CHARACTER_BASIC skip |
| 09:26 | `1acf62` | **스케줄러 재구조화** — daily OCID/BASIC (3AM) + continuous ITEM_EQUIPMENT loop. mutual exclusion lock, `@PreDestroy` graceful shutdown |
| 10:31 | `a632a3` | 스케줄 default enable |

### 1-3. 청크 기반 스냅샷 스토리지 (17:03)

| 커밋 | 내용 |
|------|------|
| `8e13021` | per-OCID `.json.gz` → streaming gzip JSONL chunk. `ChunkedSnapshotSink`(single-writer + ArrayBlockingQueue), Kafka 이벤트 발행(claim-check pattern). **5시간 soak test: 288K records, 0 failures, G1GC heap ~400MB** |

이 커밋이 calculator 모듈의 입력 소스가 된다.

---

## Phase 2: module-calculator — 새 설계와 기존 인프라의 결합 (5/5)

이 과정이 이 문서의 핵심이다. calculator 모듈은 **두 단계**를 거쳤다.

### 2-1. 1단계: 순수 새 설계, 계산 없음 (`a5cc9d8`, 07:41)

```
의존성: module-common, module-core만 (module-infra 없음)
Pipeline: Reader(IO) → Channel<String> → N Workers(CPU)
Workers: JSON 파싱 + preset 추출 + count 집계만
실제 계산 로직: 없음
```

- Coroutine Channel 기반 pipeline은 완전히 새 설계
- `Channel<String>`으로 raw JSON line을 전달, 중간 객체(SnapshotRecord, ParsedRecord) 할당 제거
- Reader는 순수 IO (gzip + line read), Workers가 모든 JSON 파싱 담당
- 결과: **500 records 처리 1.05s** (이전 구현 3.3s 대비 3.2x)

**이 단계에서 계산 엔진은 전혀 연결되지 않았다.** Pipeline 자체의 성능을 먼저 검증한 것이다.

### 2-2. 2단계: 기존 계산 인프라 연결 (`90af01fb`, 09:23, 1.5시간 후)

```
변경 사항:
1. module-infra 의존성 추가
2. 기존 계산 코드를 module-app → module-infra로 이동
3. Channel<String> → Channel<FlatItem>으로 pipeline 재설계
4. EquipmentExpectationCalculatorFactory로 실제 계산 수행
5. CalculationPortConfig로 공유 bean 구성
```

이 커밋의 본질:
- **Pipeline 아키텍처**: 새 설계 유지 (coroutine Channel, flat worker)
- **계산 로직**: 기존 것을 재사용 (V4 decorator chain, cube engine, starforce 등)
- 파일 이동만 있고 계산 코드 자체는 수정하지 않음
- 414K 줄 변경 중 413K는 `cube_probability.csv`

결과: **35K items in 4.4s** (parallelStream 대비 2.4x)

### 2-3. 3단계: Caffeine 캐시 + 병렬 파싱 + Manual ACK (`77c150c`, 10:28)

```
추가 사항:
- CalculationCache: Caffeine 캐시 (potential/additional/starforce 분리)
- 2-stage 병렬 JSON 파싱: Channel<String> → Channel<FlatItem>
- Kafka manual ack (AckMode.MANUAL): 처리 완료 후 offset commit
```

### 2-4. 4단계: 결과 이벤트 발행 (`a0a0f9f`, 11:10)

Calculator → downstream으로 결과 청크 이벤트 발행.

---

## Phase 3: 버그 수정 + 코드 리뷰 대응 (5/5 ~ 5/6)

| 시간 | 커밋 | 내용 |
|------|------|------|
| 13:00 | `cfc158d` | 개별 calculator 메서드 → `createFullCalculator()` 전환 (지원하지 않는 아이템 타입 예외) |
| 15:53 | `385a25c` | **코드 리뷰 P1/P2 6건 수정**: Kafka 발행 동기화, consumer ack 타이밍, WebClient timeout, queue timeout, CompletionException 언래핑 |
| 21:34 | `a7bba1c` | PR #811: 모든 아이템 타입에 createFullCalculator 적용 |
| 5/6 02:24 | `79c2b40` | CalculationCache 재작성 — 개별 메서드 대신 단일 Caffeine 캐시(100K entries) |

---

## 인사이트: AI의 한계와 인간 설계의 필요성

### AI가 한 일

- external-api 스켈레톤 코드 생성
- coroutine pipeline 보일러플레이트 작성
- 코드 리뷰 후 수정사항 적용 (P1/P2 대응)
- 빌드/컴파일 에러 수정

### AI가 하지 못한 것 — 인간이 설계한 것

1. **병목 위치 판단**: PGMQ 파이프라인에서 계산 단계가 병목이라는 것을 발견한 것은 단계적 테스트를 통한 인간의 판단이었다. AI는 로그를 읽을 수는 있지만, "이 병목은 아키텍처 수준의 재설계가 필요하다"는 결론을 내리지 못한다.

2. **2단계 설계 전략**: Pipeline 성능을 먼저 검증하고(1단계), 그 위에 계산 엔진을 올리는(2단계) 전략. 이것은 경험적 판단이다. AI는 한 번에 전체를 구현하려는 경향이 있다.

3. **재사용 vs 재작성 판단**: Pipeline 아키텍처는 새로 설계하되, 계산 로직(cube engine, starforce)은 기존 것을 재사용한다는 판단. 이 trade-off 분석은 AI의 범위 밖이다.

4. **점진적 커밋 전략**: 각 커밋이 독립적으로 검증 가능하도록 쪼갠 것. `a5cc9d8`은 계산 없이 pipeline만, `90af01fb`은 기존 코드 이동 + wiring만. AI는 보통 한 커밋에 모든 것을 넣으려 한다.

### 교훈

> AI는 "어떻게 구현할지"에는 강하지만, "무엇을 설계할지"와 "언제 검증할지"에는 약하다.
> 병목 추적 → 설계 결정 → 점진적 검증의 루프는 여전히 인간의 영역이다.

---

## Architecture: Before vs After

```
[Before] 기존 V5 파이프라인
  PGMQ → ExternalApiWorker → PGMQ → Calculator(inline) → PGMQ → Projection
                         계산이 동기적으로 inline 처리됨 → 병목

[After] module-external-api + module-calculator 분리
  external-api: Nexon API → JSONL chunk → Kafka(SNAPSHOT_CHUNK_READY)
  calculator:   Kafka consume → coroutine pipeline → Caffeine cache → 결과 발행
  두 모듈이 Kafka 이벤트로 느슨하게 연결 (claim-check pattern)
```

---

## Timeline Summary

```
5/4 04:40  external-api 스켈레톤
5/4 06:34  실제 Nexon API 연결 (200/s)
5/4 09:26  3단계 스케줄러 재구조화
5/4 17:03  Chunked JSONL + Kafka 이벤트
     ↓
5/5 07:41  calculator 1단계: 순수 pipeline (계산 없음)
5/5 09:23  calculator 2단계: 기존 계산 엔진 wiring
5/5 10:28  calculator 3단계: Caffeine cache + 병렬 파싱 + manual ack
5/5 11:10  calculator 4단계: 결과 이벤트 발행
     ↓
5/5 13:00  버그 수정: createFullCalculator 전환
5/5 15:53  코드 리뷰 P1/P2 대응 (6건)
5/6 02:24  CalculationCache 재작성 완료
```
