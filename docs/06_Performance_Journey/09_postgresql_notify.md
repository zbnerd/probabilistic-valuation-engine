# 9장: 최후의 도약 — PostgreSQL NOTIFY

> "Redis를 버린 다음 날, 더 빠른 Redis를 발견했다."

## 문제: 캐시 무효화의 빈자리

8장에서 Redis를 제거했다. 캐시, 락, 큐 — 모두 PostgreSQL로 이전했다. 하나를 남겼다. **캐시 무효화 전파**.

V5 Stateless 아키텍처에서는 인스턴스 A가 데이터를 갱신하면 인스턴스 B, C의 L1 캐시를 무효화해야 한다. 6장에서 이 문제 때문에 RPS를 반 토막냈던 적이 있다.

```
문제 회상 (6장):
Instance A: 아덜 totalExpectedCost = 343,523,928,885,098 ← 최신
Instance B: 아덜 totalExpectedCost = 342,100,000,000,000 ← 구버전
→ 사용자가 새로고침할 때마다 다른 결과
```

당시에는 Redis Pub/Sub으로 해결했지만, 이제 Redis가 없다. 어떻게 다중 인스턴스 간 캐시 정합성을 보장할 것인가?

## 세 가지 선택지

```
┌─────────────────────────────────────────────────────────────────┐
│  Option A: PostgreSQL LISTEN/NOTIFY                             │
│  PG 네이티브 비동기 알림. 추가 인프라 없음.                      │
│  → 트랜잭션 내 발행 가능 (원자성!)                              │
│  → 8KB 페이로드 제한                                            │
│  → 전용 LISTEN 연결 필요                                        │
│                                                                 │
│  Option B: PGMQ 폴링                                            │
│  메시지 큐에 이벤트를 넣고 주기적으로 폴링.                      │
│  → 메시지 영속성 보장                                           │
│  → 하지만 실시간 전파에 부적합 (폴링 간격만큼 지연)              │
│                                                                 │
│  Option C: TTL만 의존                                           │
│  캐시 만료 시간에만 의존. 정합성 포기.                           │
│  → 구현이 간단하지만 최대 60분간 불일치 가능                    │
└─────────────────────────────────────────────────────────────────┘
```

선택은 **A안: LISTEN/NOTIFY**였다. 이유는 하나였다.

> **트랜잭션 내에서 NOTIFY를 실행하면, 커밋될 때만 알림이 전송된다. 롤백되면 알림도 사라진다.**

Redis Pub/Sub에는 없던 **원자성**이다. Redis에서는 데이터 쓰기와 Pub/Sub 발행이 별도 작업이라, 그 사이에 장애가 나면 무효화 이벤트가 유실된다. PostgreSQL NOTIFY는 그것이 불가능하다.

## 구현: PostgresNotifySubscriber

```kotlin
@Component
class PostgresNotifySubscriber(
    private val dataSource: DataSource,
) {
    fun subscribe() {
        // 1. LISTEN용 전용 연결 생성 (Connection Pool에서 제외)
        val conn = dataSource.connection
        conn.autoCommit = false

        // 2. 채널 구독
        conn.createStatement().use { it.execute("LISTEN \"cache_invalidation\"") }

        // 3. 알림 수신 스레드 시작
        startNotificationListener(conn)
    }

    private fun startNotificationListener(conn: Connection) {
        Thread {
            while (isListening) {
                // PGConnection에서 NOTIFIES 가져오기 (Blocking)
                val pgConn = conn.unwrap(PGConnection::class.java)
                val notifications = pgConn.notifications

                notifications?.forEach { notification ->
                    // L1 캐시 무효화
                    val key = notification.parameter
                    caffeineCache.evict(key)
                }

                Thread.sleep(100)  // 폴링 간격
            }
        }.apply {
            name = "postgres-pubsub-listener"
            isDaemon = true
            start()
        }
    }
}
```

발행 쪽은 더 간단하다:

```kotlin
// 트랜잭션 내에서 실행
jdbcTemplate.execute("NOTIFY \"cache_invalidation\", '$payload'")
// 커밋 시점에 실제 전송됨
```

### 아키텍처

```
┌─────────────────┐     NOTIFY      ┌─────────────────┐
│   Instance A    │ ────────────────▶│   Instance B    │
│   (Writer)      │                  │   (Reader)      │
│                 │                  │                  │
│  1. UPDATE data │                  │  4. LISTEN 수신  │
│  2. NOTIFY      │                  │  5. L1 evict    │
│  3. COMMIT ─────┤─── atomic! ─────▶│  6. L2에서 재조회│
└─────────────────┘                  └─────────────────┘
         │                                    │
         └──────────── PostgreSQL ────────────┘
```

핵심은 **NOTIFY와 UPDATE가 같은 트랜잭션 안에 있다**는 것. 커밋이 실패하면 무효화 이벤트도 발생하지 않는다. 정합성이 깨질 가능성이 원천 차단된다.

## 첫 번째 측정: 7,347 RPS

2026년 3월 19일, 첫 부하 테스트:

```
╔════════════════════════════════════════════════════════════╗
║  PostgreSQL LISTEN/NOTIFY — First Run                       ║
║  wrk -t4 -c200 -d120s                                      ║
║                                                            ║
║  - RPS:       7,347                                        ║
║  - p99:       36ms                                         ║
║  - Errors:    65 (0.27%)                                   ║
║  - NOTIFY 전파 지연: < 50ms                                ║
╚════════════════════════════════════════════════════════════╝
```

이전 최고 기록인 940 RPS에서 **7,347 RPS**. 940 대비 **681% 향상** (97 대비 **76배**).

> **Note**: 이 향상은 LISTEN/NOTIFY 단독의 결과가 아니다. 8장에서 적용한 **Micro-Batching**(PR #608, #618)이 캐시 미스 시 DB 왕복을 3~5회에서 1회로 줄인 것이 핵심 성능 엔진이었다. LISTEN/NOTIFY는 다중 인스턴스 간 캐시 정합성을 보장하여 Micro-Batching의 이점이 Scale-out 환경에서도 유효하도록 만들었다.

하지만 65개의 에러가 있었다. 그리고 이 에러의 패턴이 이상했다. NOTIFY가 정상적으로 전송되었는데도 일부 인스턴스에서 캐시 무효화가 누락되었다.

## 버그: `doPublish()` 호출 경로 누락

추적해보니 `TransactionalCacheInvalidationListener`에서 이벤트 발행 호출 경로가 누락되어 있었다. `doPublish()` 메서드 자체는 존재했지만, 핸들러에서 호출하는 로직이 빠져 있었다.

```kotlin
// 문제: 이벤트 핸들러에서 doPublish() 호출이 누락됨
fun onCacheInvalidation(event: CacheInvalidationEvent) {
    val event = CacheInvalidationEvent(cacheName, key, type)
    // ← doPublish(event) 호출이 없었음!
    afterCommit {
        localEvict(event)
    }
}

// 수정: 발행 경로 추가
fun onCacheInvalidation(event: CacheInvalidationEvent) {
    val event = CacheInvalidationEvent(cacheName, key, type)
    executor.executeOrDefault({ doPublish(event) }, false, context)  // ← 추가
    afterCommit {
        localEvict(event)
    }
}
```

로컬 L1 무효화는 정상적으로 동작했지만, 다른 인스턴스로의 전파가 누락되고 있었다. 이것이 65개 에러의 원인이었다.

또한 채널 구조도 최적화했다:

```
Before: cache_invalidation_equipment_expectation (캐시별 분리)
After:  cache_invalidation (통합 채널)
```

분리된 채널이 관리 복잡도만 높일 뿐 이점이 없었다. 하나의 채널에서 모든 무효화 이벤트를 처리하도록 통합했다.

## 두 번째 측정: 7,347 RPS (Post-Fix)

2026년 3월 20일, 버그 수정 후:

```
╔════════════════════════════════════════════════════════════╗
║  PostgreSQL LISTEN/NOTIFY — Post-Fix                        ║
║  wrk -t4 -c200 -d120s                                      ║
║                                                             ║
║  Baseline (50 conn):   4,098 RPS, p99 162ms               ║
║  Post-Fix (200 conn):  7,347 RPS, p99 36ms                ║
║  Target  (500 conn):  10,994 RPS, p99 130ms               ║
║  Errors: 0 (Zero!)                                         ║
╚════════════════════════════════════════════════════════════╝
```

**7,347 RPS. 에러 0개.** Post-Fix 기준으로 **76배 향상**.

500 연결에서 10,994 RPS도 기록했지만, 200연결의 7,347이 더 현실적인 지표였다. 이후 실데이터 검증에서도 이 수치가 기준점이 되었다.

## 왜 이렇게 빠른가?

Redis Pub/Sub을 쓸 때보다 빨랐던 이유를 분석했다. 단, **LISTEN/NOTIFY는 캐시 정합성 해결책**이지 성능 엔진 자체는 아니다. 실제 성능 향상의 주된 원인은 8장의 Micro-Batching이었다.

| 요소 | Redis Pub/Sub | PostgreSQL NOTIFY |
|------|--------------|-------------------|
| 발행 지연 | ~1ms (측정) | ~2-5ms (측정) |
| 전파 지연 | ~5ms (측정) | ~10-20ms (측정) |
| **네트워크 홉** | **App → Redis → App (2홉)** | **App → PG → App (1홉, 같은 연결)** |
| **트랜잭션** | **별도 (비원자적)** | **동일 트랜잭션 (원자적)** |
| **인프라** | **Redis 프로세스 필요** | **이미 연결된 PG 사용** |

Redis가 발행 자체는 더 빠르지만, 실제 병목은 네트워크 홉 수와 인프라 오버헤드였다. PostgreSQL은 이미 연결되어 있으므로 추가 연결이 필요 없다.

더 중요한 것은 **원자성이 보장**되면서 캐시 무효화 실패로 인한 재시도/재조회가 사라졌다는 점이다. Redis에서는 가끔 무효화가 누락되어 구버전 데이터가 캐시에 남아 있었고, TTL 만료까지 기다려야 했다. PostgreSQL NOTIFY에서는 그런 일이 원천적으로 발생하지 않는다.

### 최종 아키텍처

```
┌───────────────────────────────────────────────────────────────┐
│                        Load Balancer                          │
└───────────────────────────────────────────────────────────────┘
         │                    │                    │
         ▼                    ▼                    ▼
┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐
│   Instance A    │  │   Instance B    │  │   Instance C    │
│                 │  │                 │  │                 │
│  ┌───────────┐  │  │  ┌───────────┐  │  │  ┌───────────┐  │
│  │ L1 Caffeine│  │  │  │ L1 Caffeine│  │  │  │ L1 Caffeine│  │
│  │  (5000)    │  │  │  │  (5000)    │  │  │  │  (5000)    │  │
│  └─────┬─────┘  │  │  └─────┬─────┘  │  │  └─────┬─────┘  │
│        │        │  │        │        │  │        │        │
│  ┌─────▼─────┐  │  │  ┌─────▼─────┐  │  │  ┌─────▼─────┐  │
│  │ LISTEN    │◄─┼──┼──│ NOTIFY    │──┼──┼──│ NOTIFY    │  │
│  │ (pg conn) │  │  │  │ (in tx)   │  │  │  │ (in tx)   │  │
│  └─────┬─────┘  │  │  └─────┬─────┘  │  │  └─────┬─────┘  │
└────────┼────────┘  └────────┼────────┘  └────────┼────────┘
         └────────────────────┼────────────────────┘
                              ▼
                    ┌─────────────────┐
                    │   PostgreSQL    │
                    │   L2 UNLOGGED   │
                    │   NOTIFY        │
                    └─────────────────┘
```

노드가 추가되면 LISTEN 연결만 하나 더 맺으면 된다. 자동으로 모든 캐시 무효화 이벤트를 수신한다. 추가 인프라도, 설정 변경도 필요 없다.

## 트레이드오프

| 항목 | 이점 | 대가 |
|------|------|------|
| **원자적 무효화** | 데이터 정합성 100% 보장 | — |
| **추가 인프라 없음** | PostgreSQL 하나로 해결 | 전용 LISTEN 연결 1개 소모 |
| **선형 확장** | 노드 추가만으로 Scale-out | 노드당 PG 커넥션 +1 |
| **8KB 제한** | 캐시 키만 전달하면 충분 | 대형 페이로드는 DB 조회 필요 |

## 주의점

NOTIFY는 만병통치약이 아니다. 8KB 페이로드 제한이 있어 캐시 키만 전달해야 한다. 와일드카드 구독도 불가능하다. 그리고 LISTEN용 연결은 커넥션 풀에서 제외하고 직접 관리해야 한다.

하지만 **캐시 무효화**라는 목적에는 완벽했다. 캐시 키는 보통 50바이트 미만이고, 채널은 하나면 충분하며, 연결 관리는 daemon 스레드로 자동 처리된다.

## 새로운 질문

10,994 RPS는 **빈 데이터베이스**에서의 수치였다. 캐시에 5,000개 엔트리, DB에 몇 백 개 로우만 있는 상태. 실제 운영 환경에서는 수십만 개의 데이터가 있을 것이다.

> **"30만 개의 데이터가 있을 때도 10,000 RPS가 나올까?"**

이 질문에 대한 답이 다음 장을 쓰게 했다.

---

> **이 시점의 RPS: 10,994 (빈 DB, 캐시만 활성화)**
> **관련 이슈**: #547, #548, #551, #562
> **관련 ADR**: ADR-006, ADR-027

**다음 장**: [10장 — 현실의 벽: 수십만 데이터로 검증하다](./10_real_data_challenge.md)
