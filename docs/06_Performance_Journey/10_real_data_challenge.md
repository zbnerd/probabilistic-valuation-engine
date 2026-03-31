# 10장: 현실의 벽 — 수십만 데이터로 검증하다

> "벤치마크는 거짓말을 한다. 진실은 진짜 데이터 안에 있다."

## 문제: 10,994 RPS의 조건

9장에서 10,994 RPS를 달성했다. 팀은 환호했다. 하지만 그 수치에는 중요한 전제가 붙어 있었다.

```
테스트 환경 (9장):
- DB rows: 약 500개
- L1 cache entries: 약 100개
- 캐시 히트율: 99.99%
- Cold miss: 거의 없음
- PostgreSQL upsert 경합: 없음
```

현실은 달랐다. 서비스가 성장하면 DB에는 수십만 개의 `equipment_expectation_summary` 로우가 쌓인다. 캐시도 수천 개의 엔트리를 유지해야 한다. 그리고 사용자는 항상 같은 캐릭터만 조회하지 않는다.

**질문: 실제 운영 환경에서는 몇 RPS가 나올까?**

답을 위해 두 가지가 필요했다:
1. **실 데이터**: 30만 개 캐릭터의 기대비용 계산 결과
2. **실 부하**: 다양한 캐릭터 분포로 부하 테스트

## 30만 개 벌크 로딩

2026년 3월 22일, 30만 개 캐릭터 데이터를 DB에 밀어 넣는 작업을 시작했다.

### 문제: 단순 반복은 8.3시간

30만 개를 순차 처리하면 300,000 × 100ms = 8.3시간. 게다가 Nexon API Rate Limit에 걸리면 더 길어진다.

```
단순 반복의 문제:
300,000 캐릭터 × 100ms/개 = 8.3시간
429 Rate Limit 발생 시 → 지수 백오프 → 12시간+
장애 발생 시 → 처음부터 다시 (재개 불가)
```

### 해결: 적응형 벌크 로더

Semaphore(100) 기반 동시성 제어 + 적응형 쓰로틀링 + 체크포인트 재개:

```kotlin
// 핵심: API 상태에 따른 동적 속도 조절
when (result) {
    is Success -> {
        batchSize = min(batchSize + 10, maxBatchSize)  // 속도 올림
        delay = max(delay - 10, minDelay)
    }
    is RateLimited -> {
        batchSize = max(batchSize / 2, minBatchSize)   // 속도 내림
        delay = min(delay * 2, maxDelay)                // 지수 백오프
    }
    is Timeout -> {
        delay = min(delay * 1.5, maxDelay)              // 중간 백오프
    }
}
```

체크포인트는 500개마다 저장. 장애 후 재개 가능:

```
[BulkLoader] Loaded 15000/300000 (5.0%) | ETA: 45min | Errors: 12 | Rate: 100/sec
[BulkLoader] Loaded 30000/300000 (10.0%) | ETA: 40min | Errors: 28 | Rate: 95/sec
...
[BulkLoader] Checkpoint saved at 150000/300000
[BulkLoader] ⚠️ 429 Rate Limit detected — throttling down (batch: 100→50, delay: 100ms→200ms)
...
[BulkLoader] Complete! 298,428/300,000 (99.5%) | Errors: 1,572 | Duration: 1h 52min
```

**158,428 rows**가 최종적으로 `equipment_expectation_summary` 테이블에 저장되었다. 중복 0건.

> **Note**: 298,428개의 API 호출이 성공했지만, 모든 캐릭터가 장비 데이터를 보유한 것은 아니다. 장비가 없는 캐릭터는 계산 결과가 비어 upsert 대상에서 제외된다. 298,428 - 158,428 = 139,950건은 장비가 없거나 빈 결과를 반환한 캐릭터다.

### Write-Behind Buffer의 역할

벌크 로딩 중 Write-Behind Buffer가 빛을 발했다. 계산 결과 158,428개가 개별 upsert가 아니라 **배치 upsert**로 처리되었다.

```
Without Buffer:  158,428 × (개별 INSERT ... ON CONFLICT) = DB 폭주
With Buffer:     158,428 → Buffer.offer() → 500개 배치 → 317회 upsert
```

DB에 가해진 부하가 158,428회에서 317회로 줄었다.

## 현실의 검증: 7,347 RPS

2026년 3월 24일, LISTEN/NOTIFY 버그 픽스 + 벌크 로딩 완료 후 200k~300k rows 환경에서 측정한 수치.

```
╔════════════════════════════════════════════════════════════╗
║  PostgreSQL LISTEN/NOTIFY — Post-Fix (Real Data)            ║
║  200k~300k rows in DB                                       ║
║  wrk -t4 -c200 -d120s                                      ║
║                                                             ║
║  Post-Fix:  7,347 RPS (p99: 36ms)  ← 실데이터 환경          ║
║  Errors:    0 (Zero!)                                       ║
║                                                             ║
║  vs. 빈 DB 이상치 (500 conn): 10,994 RPS                    ║
║  → 실데이터에서 -33% (자연스러운 하락)                       ║
╚════════════════════════════════════════════════════════════╝
```

**7,347 RPS. 에러 0개. 수십만 실데이터 위에서.** 이 수치는 **순수 캐시 성능**을 보여주는 것이다.

빈 DB에서의 10,994(500 연결)는 이상적 한계치였다. 실데이터 환경에서 200 연결로 잰 7,347이 **진짜 성과**다.

## 원인 분석: 이상과 현실의 간극

빈 DB의 10,994에서 실데이터의 7,347으로. 33%의 간극이 왜 발생했는지 분석했다.

### 병목 1: CPU Pipeline (60%)

캐시 미스 시 CPU 파이프라인이 포화된다.

```
캐시 미스 시 요청 처리 (단일 요청):
┌─────────────────────────────────────────────────────────────┐
│  Nexon API Fetch:  150~572ms     (I/O)                      │
│  JSON 파싱:        200~300KB     (CPU, 가장 의심되는 병목)  │
│  3 프리셋 DP 계산: O(n³)×3      (CPU)                       │
│  Gzip 압축:        300KB → 3-4KB (CPU, 경량)                │
│  DB upsert:        ON CONFLICT  (I/O + CPU)                 │
└─────────────────────────────────────────────────────────────┘
```

> **TODO**: 상기 CPU 점유율 분석은 추정치임. async-profiler로 실제 핫스팟 측정 후 수치 업데이트 예정. ADR-086 참조.

확률 DP(Dynamic Programming) 알고리즘이 O(n³)이다. 슬롯당 30~45회 호출. 3개 프리셋이 이미 병렬화되어 있지만, 개별 프리셋 내부의 연산은 여전히 무겁다.

### 병목 2: Cache Invalidation → DB Fallback

LISTEN/NOTIFY로 인해 캐시가 invalidation되고, 그 결과 DB로 fallback하는 요청이 늘어났다.

```
UPDATE 발생
→ NOTIFY 전파
→ 다른 노드 캐시 삭제 (evict)

다음 요청:
→ 캐시 없음 (miss)
→ DB 조회 (fallback)
→ 느려짐
```

빈 DB에서는 캐시 evict가 거의 일어나지 않았다. 업데이트가 적고 캐시 엔트리도 소수. 하지만 30만 개 데이터 환경에서는 업데이트가 빈번해지고, 그만큼 캐시 invalidation도 자주 발생한다.

| 경로 | 시간 |
|------|------|
| 캐시 hit (fast path) | ~4ms |
| DB 조회 (fallback) | 수십~수백 ms |

캐시 hit ratio가 99.99%에서 조금만 떨어져도 DB fallback 요청이 급증한다. 이것이 33% 성능 감소의 핵심 원인이다.

```
LISTEN/NOTIFY → 캐시 invalidation → cache miss 증가 → DB fallback 증가 → RPS 감소
```

> UPDATE 자체가 느려진 것이 아니다. UPDATE로 인해 캐시가 깨져서 DB를 보게 된 것이 핵심이다.

### 병목 3: PostgreSQL Write Amplification

158,428 rows가 있는 상태에서 upsert 비용이 증가했다.

```
Write Amplification 원인:
├── Unique Index: uk_character_preset (game_character_id, preset_no)
│   → INSERT/UPDATE 시 인덱스 갱신 필요
├── WAL (Write-Ahead Log):
│   → upsert = DELETE + INSERT → 2배의 WAL 발생
├── Autovacuum:
│   → dead tuple 누적 → vacuum 부하
└── Checkpoint:
    → WAL 누적 → checkpoint 시 I/O 스파이크
```

빈 DB에서는 인덱스가 메모리에 올라가 있어 1ms였던 upsert가, 15만 rows에서는 디스크 I/O가 발생하면서 5~15ms로 늘어났다.

## 근본 인사이트

> **"운영 병목과 백필 병목을 분리해서 봐야 한다."**

이것이 가장 중요한 깨달음이었다.

| 병목 유형 | 주요 원인 | 해결 방법 |
|-----------|----------|----------|
| **운영 Read Path** | Cache invalidation → DB fallback | 캐시 웜업, L2 최적화 |
| **운영 Read Path** | CPU pipeline (parse/calc/compress) | 프로파일링 → 최적화 |
| **백필 Write Path** | PostgreSQL upsert write amplification | Staging table + merge |

운영 중의 읽기 병목과 대량 적재 시의 쓰기 병목은 **완전히 다른 문제**다. 같은 큐에 넣으면 서로를 방해한다.

> **Note**: Fan-Out 시나리오(서로 다른 키의 동시 대량 요청)와 이에 대한 Admission Control은 별도 챕터에서 다룬다. 이 장에서는 실데이터 환경에서 실제로 관측된 병목에 집중한다.

### 대책: Write Path 분리

운영 Write-Behind Buffer와 백필 Bulk Loader를 완전히 분리했다.

```
Before (혼합):
Write-Behind Buffer ← 운영 요청 + 백필 요청이 같은 큐 사용
→ 백필이 운영 요청을 밀어냄

After (분리):
운영 Write-Behind Buffer ← 실시간 요청만 (5초 flush)
백필 Bulk Loader ← 대량 처리만 (Staging table + merge)
→ 서로 독립적으로 동작
```

## 7,347 RPS가 의미하는 것

10,994(이상적)에서 7,347(현실)로. 33% 하락. 실패인가?

**아니다.** 7,347 RPS는 **수십만 실데이터 위에서의 진짜 성능**이다.

```
비교:
97 RPS (시작)           → 단일 인스턴스, 동기 처리
10,994 RPS (이상적)     → 빈 DB, 500 연결, 100% 캐시 히트
7,347 RPS (현실)        → 200k~300k rows, 200 연결, 혼합 부하

7,347 RPS × 300KB 응답(raw JSON) = 2.2 GB/s 데이터 처리
= 일반 2KB API 기준으로 330,000 RPS에 해당 (GZIP 후 ~3-4KB)
```

97 RPS에서 7,347 RPS. **76배 향상.** 그것도 실데이터 환경에서.

### Scale-out 시 예상

현재 단일 인스턴스에서 7,347 RPS. LISTEN/NOTIFY로 캐시 정합성이 보장되므로, 인스턴스를 추가하면 선형 확장이 가능하다.

```
1 인스턴스:  7,347 RPS (검증 완료)
2 인스턴스: ~14,000 RPS (예상)
4 인스턴스: ~28,000 RPS (예상)
```

추가 인프라 없이 PostgreSQL 연결만 늘어난다.

## 트레이드오프

| 항목 | 이점 | 대가 |
|------|------|------|
| **Admission Control** | CPU 포화 방지 | 초과 요청 503 응답 (Retry-After 제공) |
| **Write Path 분리** | 운영/백필 독립 | 구현 복잡도 증가 |
| **실데이터 테스트** | 현실적 성능 파악 | 이상적 수치 하락 |
| **LISTEN/NOTIFY** | 선형 확장 보장 | 노드당 커넥션 +1 |

## 아직 해결하지 못한 것

1. **CPU 프로파일링**: async-profiler로 실제 핫스팟을 측정하지 못했다. 위의 CPU 점유율 분석은 **추정치**이며 프로파일링으로 검증되지 않았다.

2. **Changed-Only Upsert**: 값이 변하지 않은 캐릭터의 upsert를 스킵하는 dirty tracking. 구현하면 write를 30-50% 줄일 수 있지만 아직 구현 전이다.

3. **JSON 부분 파싱**: 200-300KB JSON에서 필요한 필드만 파싱. CPU를 25% → 10%로 줄일 수 있지만 아직 구현 전이다.

이것들은 다음 스프린트에서 해결할 과제다. 하지만 이미 7,347 RPS를 달성했고, Scale-out 준비도 되어 있다.

## 배운 점

> **"빈 데이터베이스에서의 벤치마크는 최적화의 시작점이지, 끝이 아니다."**

10,994라는 수치는 자랑스러웠지만, 7,347이라는 수치가 더 정직했다. 그리고 이 정직한 수치 위에서 Scale-out 계획을 세울 수 있었다.

10,994 RPS를 믿고 운영에 들어갔으면 첫날 장애가 났을 것이다. 7,347 RPS를 알고 있으니, 트래픽이 7,000 RPS를 넘으면 인스턴스를 추가하면 된다. **측정은 보수적으로, 계획은 공격적으로.**

---

> **이 시점의 RPS: ~7,347 (200k~300k rows, LISTEN/NOTIFY Post-Fix)**
> **관련 이슈**: #611 (Bulk Load)
> **별도 챕터 예정**: Fan-Out / Admission Control (#617, #623)
> **관련 ADR**: ADR-028, ADR-086, ADR-030

**다음 장**: [11장 — 보이지 않는 폭발: Fan-Out과 Admission Control](./11_fanout_admission_control.md)
