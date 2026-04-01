# 4장: 뇌우 — 캐시 스탬피드와 SingleFlight의 깨달음

> "캐시가 만료되는 순간, 100개의 동일한 쿼리가 DB로 쏟아졌다."
>
> — ADR-003, 다계층 캐시 및 SingleFlight 패턴 도입

---

## 발생: 인기 캐릭터의 저주

특정 캐릭터가 인기였다. 메이플스토리에서 유명한 캐릭터 하나의 데이터가 캐시에 올라가 있었다. 수백 명의 사용자가 동시에 이 캐릭터를 검색한다. 캐시 TTL이 만료되는 순간 —

**100개의 동일한 쿼리가 동시에 DB로 쏟아졌다.**

캐시 스탬피드(Cache Stampede). 번개가 한 곳에 100번 내리친 것과 같았다.

---

## 탐지: DB가 신음하기 시작했다

```
DB 쿼리 패턴:

[00:00.000] SELECT * FROM character WHERE name = '인기캐릭터'  -- 캐시 만료
[00:00.001] SELECT * FROM character WHERE name = '인기캐릭터'  -- 동일 쿼리
[00:00.001] SELECT * FROM character WHERE name = '인기캐릭터'  -- 동일 쿼리
[00:00.002] SELECT * FROM character WHERE name = '인기캐릭터'  -- 동일 쿼리
... (100개의 동일 쿼리)
```

하나의 캐시 키에 대해 100개의 동일한 DB 쿼리. DB는 같은 작업을 100번 반복했다. 커넥션 풀은 순식간에 고갈되었다.

p99 지연 시간은 **2,340ms**까지 치솟았다.

---

## 분석: Cache Stampede의 메커니즘

```
시간 T: 캐시 만료
시간 T+0ms: 요청 1 도달 → 캐시 MISS → DB 쿼리 시작
시간 T+0ms: 요청 2 도달 → 캐시 MISS → DB 쿼리 시작
시간 T+0ms: 요청 3 도달 → 캐시 MISS → DB 쿼리 시작
...
시간 T+0ms: 요청 100 도달 → 캐시 MISS → DB 쿼리 시작
시간 T+50ms: 첫 번째 쿼리 완료 → 캐시 갱신
시간 T+50ms: 나머지 99개 쿼리도 거의 동시에 완료 → 캐시 덮어쓰기
```

문제는 **Leader가 없다**는 것이었다. 모든 요청이 자신이 Leader라고 생각하고 DB로 갔다. 하나의 요청만 DB에 가고 나머지는 기다렸다 가면 되는데, 그 메커니즘이 없었다.

---

## 대응: TieredCache + SingleFlight

### ADR-003: 세 계층의 방어

```
L1 (Caffeine) → L2 (PostgreSQL UNLOGGED) → SingleFlight → Loader
```

**L1 — Caffeine 로컬 캐시.** 인스턴스 내 메모리. 5ms 미만의 접근 속도.

**L2 — PostgreSQL UNLOGGED 테이블.** 인스턴스 간 공유 캐시. 20ms 미만.

**SingleFlight — Leader/Follower 패턴.** 동일한 키에 대한 동시 요청을 하나로 합친다.

**Loader — 실제 데이터 로드.** DB 쿼리 또는 외부 API 호출. 가장 비싼 연산.

### Leader/Follower 패턴

```
요청 1: 캐시 MISS → Leader 선출 → DB 쿼리 시작
요청 2: 캐시 MISS → Follower → Leader 완료 대기
요청 3: 캐시 MISS → Follower → Leader 완료 대기
...
요청 100: 캐시 MISS → Follower → Leader 완료 대기

Leader 완료 → L2 저장 → L1 저장 → 모든 Follower에게 통지
Follower들: L2에서 데이터 읽기 → 응답
```

100개의 DB 쿼리가 1개로 줄었다. **99% 감소.**

### Follower Timeout

Follower가 무한정 기다릴 수는 없다. Leader가 장애나면 Follower도 함께 죽는다.

```kotlin
val followerTimeout = 300ms  // 시스템 타임아웃의 90%
```

300ms 내에 Leader가 완료되지 않으면 Follower는 독립적으로 폴백을 실행한다. Leader가 죽어도 Follower는 산다.

---

## "완벽한 최적화"가 역효과를 낸 이야기

TieredCache 도입 후, 한 가지 더 최적화를 시도했다. **LocalSingleFlight.**

로컬 인스턴스 내에서 동일한 키의 요청을 합치는 것. 분산 락 없이도 된다. 완벽해 보였다.

하지만 결과는 **역효과**였다.

```
Before LocalSingleFlight: 555 RPS
After LocalSingleFlight:  97 RPS ← -56% regression!
```

**원인:** LocalSingleFlight가 **캐시 히트마저 블록**했다.

```kotlin
// Anti-Pattern: 캐시 히트도 SingleFlight 통과
fun get(key: String): Data {
    return singleFlight.execute(key) {
        // 이 블록 안에 캐시 조회도 포함됨
        val cached = l1Cache.get(key)
        if (cached != null) return@execute cached  // 캐시 히트!
        // DB 조회...
    }
}
```

SingleFlight는 키별로 하나의 실행만 허용한다. 캐시 히트가 발생해도 다른 요청은 기다린다. 100개의 캐시 히트 요청이 직렬로 처리되는 것. 병렬의 이점이 완전히 사라졌다.

**해결:** SingleFlight는 캐시 MISS에만 적용.

```kotlin
fun get(key: String): Data {
    // 1. L1 조회 (SingleFlight 밖)
    val cached = l1Cache.get(key)
    if (cached != null) return cached

    // 2. SingleFlight는 캐시 MISS에만
    return singleFlight.execute(key) {
        // L2 → DB 조회
    }
}
```

RPS 복구: 97 → **555 RPS** (+473%).

---

## L1 Fast Path: 직렬화의 제거

캐시 히트가 빠르긴 하지만, 여전히 직렬화/역직렬화 비용이 있었다.

```
기존 캐시 히트 흐름:
JSON String → Deserialize → Object → Serialize → Response
```

매번 JSON을 파싱하고 다시 직렬화한다. 캐시에 있으면 그냥 바이트 배열 그대로 주면 되지 않을까?

**L1 Fast Path:**

```kotlin
// GZIP 압축된 바이트 배열을 그대로 반환
fun getFast(key: String): ByteArray? {
    return l1Cache.get(key)  // zero-copy
}
```

캐시에 저장할 때 GZIP으로 압축된 바이트 배열을 그대로 넣고, 읽을 때도 그대로 꺼낸다. 직렬화/역직렬화 없이. 응답은 압축된 상태로 그대로 전송.

```
Before: JSON → Deserialize → Object → Serialize → GZIP → Response
After:  GZIP ByteArray → Response (zero-copy)
```

RPS: 555 → **674 RPS** (+21%).

---

## 장애대응 테스트로 검증

### Scenario: Cache Stampede

```
장애대응 테스트: 캐시 만료 순간 100개 동시 요청

Before SingleFlight:
  DB 쿼리 수: 100
  p99 지연: 2,340ms
  커넥션 풀: 고갈

After SingleFlight:
  DB 쿼리 수: 1 (-99%) ✅
  p99 지연: 180ms (-92%) ✅
  커넥션 풀: 안정
  캐시 히트율: 99.7%
  DB Query Ratio: 0.3% (임계치 1% 미만)
```

### Scenario: Celebrity Problem (N05)

인기 캐릭터에 대한 집중 공격.

```
장애대응 테스트: 단일 키에 1,000 RPS

DB Query Ratio: 0.1-0.8% (PASS)
Fallback Rate: < 1%
데이터 무결성: 100%
```

### Scenario: Thundering Herd (N01)

캐시 전체가 무효화되는 시나리오.

```
장애대응 테스트: 전체 캐시 무효화 + 동시 요청 폭주

스레드: 100
성공: 87, 타임아웃: 13
평균 대기: 523ms
최대 대기: 12,456ms
데이터 무결성: 5,000/5,000 (100%) ✅
```

---

## 측정 결과

| 메트릭 | Before | After | 개선 |
|--------|--------|-------|------|
| DB 쿼리 (스탬피드 시) | 100 | 1 | -99% |
| p99 지연 | 2,340ms | 180ms | -92% |
| 캐시 히트율 | — | 99.7% | — |
| DB Query Ratio | — | 0.3% | — |
| RPS | 97 | 555→674 | +595% |

---

## 교훈

**1. 캐시 만료는 폭풍의 눈이다.**

캐시가 있으면 안전하다고 생각하지만, 만료되는 순간이 가장 위험하다. 만료 시점의 동시 요청을 어떻게 처리할지가 진짜 문제다.

**2. "완벽한 최적화"는 없다.**

LocalSingleFlight는 이론적으로 완벽했다. 하지만 실제로는 캐시 히트마저 블록하는 역효과를 냈다. 최적화는 측정 후에.

**3. Leader/Follower는 기본 패턴이다.**

동일한 작업에 대한 동시 요청을 하나로 합치는 것은 분산 시스템의 기본이다. 이것이 없으면 스탬피드는 막을 수 없다.

**4. 측정만이 진실이다.**

555 RPS에서 97 RPS로 떨어진 것은 측정했기 때문에 알았다. 측정하지 않았으면 "최적화했으니 빨라졌겠지"라고 생각했을 것이다.

---

> **다음 장:** [5장: 가상의 그림자 — Virtual Thread Pinning과의 사투](05_virtual_thread.md)
