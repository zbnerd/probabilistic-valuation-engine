# ADR-323: PostgreSQL LISTEN/NOTIFY 캐시 무효화

## 메타데이터

| 항목 | 값 |
|------|-----|
| 상태 | 수락됨 (Accepted) |
| 결정일 | 2026-03-10 |
| 결정자 | probabilistic-valuation-engine Team |
| 검토자 | Architecture Review Board |
| 관련 이슈 | #547, #548, #551, #585 |
| 선행 ADR | ADR-001 PostgreSQL 단일 DB 전략, ADR-003 Redis 기능 PostgreSQL 대체 |

---

## 1. 배경 (Context)

### 현재 Redis Pub/Sub 사용 현황

probabilistic-valuation-engine은 **Redis Pub/Sub**을 활용하여 캐시 무효화 이벤트를 전파:

| 사용 사례 | 채널 패턴 | 페이로드 |
|----------|----------|----------|
| **캐시 Evict** | `cache:evict:{cacheName}` | `{key}` |
| **캐시 Clear All** | `cache:clear:{cacheName}` | `*` |
| **데이터 변경** | `data:change:{entity}` | `{id}` |

### Redis Pub/Sub 동작 방식

```kotlin
// RedisMessageListenerContainer (현재)
@Component
class CacheInvalidationListener(
    private val redisOperationPort: RedisOperationPort,
) {
    init {
        redisOperationPort.subscribe("cache:evict:equipment_expectation") { message ->
            // L1 캐시 무효화
            caffeineCache.evict(message)
        }
    }
}

// 발행
redisOperationPort.publish("cache:evict:equipment_expectation", "ABC123")
```

### TieredCache 현재 흐름

```
┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│  Instance A │    │   Redis     │    │ Instance B  │
│             │───>│  Pub/Sub    │───>│             │
│  evict(key) │    │             │    │ evict(key)  │
└─────────────┘    └─────────────┘    └─────────────┘
                          │
                          ▼
                   ┌─────────────┐
                   │ Instance C  │
                   │ evict(key)  │
                   └─────────────┘
```

### 문제점

| 문제 | 영향 |
|------|------|
| **Redis 의존성** | Redis 장애 시 캐시 무효화 불가 |
| **연결 오버헤드** | 별도 Pub/Sub 연결 유지 |
| **불안정성** | 네트워크 단절 시 이벤트 손실 |
| **복잡한 Reconnect** | Redis 재시작 시 자동 재연결 로직 |

---

## 2. 결정 (Decision)

**Redis Pub/Sub을 PostgreSQL LISTEN/NOTIFY로 대체한다.**

### 핵심 원칙

1. **Publisher/Listener 패턴**
   - NOTIFY: 이벤트 발행
   - LISTEN: 이벤트 구독
   - 비동기 알림 전달

2. **Connection 관리**
   - 전용 LISTEN 연결 유지
   - Reconnect 로직 구현
   - Connection Pool 분리

3. **8KB 페이로드 제한 대응**
   - 페이로드에 참조 ID만 포함
   - 상세 데이터는 DB 조회

4. **Channel 구조화**
   - `cache:{cacheName}:{operation}` 형식
   - 와일드카드 지원 (PostgreSQL 이슈로 구현 제약)

---

## 3. 대안 (Alternatives)

### A. 현상 유지 (Redis Pub/Sub)

**장점:**
- 와일드카드 패턴 지원
- 검증된 안정성

**단점:**
- Redis 의존성 지속
- 연결 오버헤드

**평가:** ❌ 단일 DB 전략 위배

### B. PostgreSQL LISTEN/NOTIFY (선택됨)

**장점:**
- PostgreSQL 네이티브
- DB 연결 하나로 해결
- 트랜잭션 내 발행 가능

**단점:**
- 8KB 페이로드 제한
- 와일드카드 미지원
- 별도 LISTEN 연결 필요

**평가:** ✅ 단일 DB 전략 부합

### C. PGMQ 기반 이벤트 버스

**장점:**
- 메시지 영속성
- 재처리 가능

**단점:**
- 실시간 전파에 부적합
- 폴링 오버헤드

**평가:** ⚠️ 캐시 무효화용으로는 과도함

---

## 4. 기술적 구현 (Implementation)

### LISTEN/NOTIFY 기본

```sql
-- 발행 (NOTIFY)
NOTIFY 'cache_evict_equipment_expectation', 'ABC123';

-- 구독 (LISTEN)
LISTEN 'cache_evict_equipment_expectation';

-- 구독 취소
UNLISTEN 'cache_evict_equipment_expectation';

-- 모든 구독 취소
UNLISTEN '*';
```

### Channel 명명 규칙

| 작업 | Channel Format | Payload |
|------|---------------|---------|
| Evict | `cache_evict_{cacheName}` | `{key}` |
| Clear All | `cache_clear_{cacheName}` | `*` |
| Data Change | `data_change_{entity}` | `{id}` |

### PostgreSQL LISTEN/NOTIFY 구현

```kotlin
// module-infra/src/main/kotlin/.../pubsub/PostgresPubSubContainer.kt
package maple.expectation.infrastructure.pubsub

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

/**
 * PostgreSQL LISTEN/NOTIFY 기반 Pub/Sub 컨테이너
 *
 * <h3>연결 관리</h3>
 *
 * <ul>
 *   <li>LISTEN용 전용 연결 유지 (Connection Pool에서 제외)</li>
 *   <li>NOTIFY는 일반 트랜잭션 연결 사용</li>
 *   <li>연결 단절 시 자동 재연결</li>
 * </ul>
 *
 * <h3>페이로드 제한</h3>
 *
 * <p>PostgreSQL NOTIFY는 8KB 페이로드 제한이 있음.
 * 캐시 키나 ID만 전달하고 상세 데이터는 별도 조회.
 *
 * @see <a href="https://www.postgresql.org/docs/current/sql-notify.html">PostgreSQL NOTIFY</a>
 */
@Component
class PostgresPubSubContainer(
    private val dataSource: DataSource,
) {
    companion object {
        private val log = LoggerFactory.getLogger(PostgresPubSubContainer::class.java)
        private const val MAX_PAYLOAD_BYTES = 8000  // 8KB 제한
    }

    private val listeners = ConcurrentHashMap<String, MutableList<(String) -> Unit>>()
    private val listenConnection: Connection = createListenConnection()
    @Volatile
    private var isListening = false

    init {
        startNotificationListener()
    }

    /**
     * 이벤트 발행 (NOTIFY)
     *
     * <h3>트랜잭션 내 발행</h3>
     *
     * <p>트랜잭션 커밋 후 실제로 전송됨.
     * 롤백 시 이벤트도 함께 롤백됨.
     */
    fun publish(channel: String, payload: String) {
        val safePayload = if (payload.toByteArray().size > MAX_PAYLOAD_BYTES) {
            log.warn("[PubSub] Payload exceeds 8KB, truncating: channel={}, size={}",
                channel, payload.toByteArray().size)
            payload.take(MAX_PAYLOAD_BYTES / 2)  // 안전하게 잘라서 한글 등 대응
        } else {
            payload
        }

        val conn = dataSource.connection
        try {
            val stmt = conn.createStatement()
            // PostgreSQL에서는 작은따옴표로 이스케이프 필요
            val escapedPayload = safePayload.replace("'", "''")
            stmt.execute("NOTIFY \"$channel\", '$escapedPayload'")
            stmt.close()
            log.debug("[PubSub] Published: channel={}, payload={}", channel, safePayload)
        } catch (e: SQLException) {
            log.error("[PubSub] Failed to publish: channel={}, payload={}", channel, safePayload, e)
            throw RuntimeException("Failed to publish notification", e)
        } finally {
            conn.close()
        }
    }

    /**
     * 이벤트 구독 (LISTEN)
     *
     * <h3>중복 구독 허용</h3>
     *
     * <p>동일 채널에 여러 리스너 등록 가능.
     * 알림 수신 시 모든 리스너에게 전달.
     */
    fun subscribe(channel: String, callback: (String) -> Unit) {
        listeners.computeIfAbsent(channel) { mutableListOf() }.add(callback)

        // 처음 구독 시 LISTEN 실행
        if (!isListening || !listeners.containsKey(channel)) {
            executeListen(channel)
        }

        log.debug("[PubSub] Subscribed: channel={}, totalListeners={}",
            channel, listeners[channel]?.size)
    }

    /**
     * 구독 취소 (UNLISTEN)
     */
    fun unsubscribe(channel: String) {
        listeners.remove(channel)
        executeUnlisten(channel)
        log.debug("[PubSub] Unsubscribed: channel={}", channel)
    }

    /**
     * LISTEN 전용 연결 생성
     *
     * <h3>Connection Pool 제외</h3>
     *
     * <p>LISTEN 연결은 장기간 유지되어야 하므로
     * Connection Pool에서 제외하고 직접 관리.
     */
    private fun createListenConnection(): Connection {
        val conn = dataSource.connection
        try {
            // autocommit=false 설정 (LISTEN 유지)
            conn.autoCommit = false
            return conn
        } catch (e: SQLException) {
            conn.close()
            throw RuntimeException("Failed to create listen connection", e)
        }
    }

    /**
     * LISTEN 실행
     */
    private fun executeListen(channel: String) {
        try {
            val stmt = listenConnection.createStatement()
            stmt.execute("LISTEN \"$channel\"")
            stmt.close()
            log.debug("[PubSub] LISTEN executed: channel={}", channel)
        } catch (e: SQLException) {
            log.error("[PubSub] Failed to LISTEN: channel={}", channel, e)
            reconnect()
        }
    }

    /**
     * UNLISTEN 실행
     */
    private fun executeUnlisten(channel: String) {
        try {
            val stmt = listenConnection.createStatement()
            stmt.execute("UNLISTEN \"$channel\"")
            stmt.close()
        } catch (e: SQLException) {
            log.error("[PubSub] Failed to UNLISTEN: channel={}", channel, e)
        }
    }

    /**
     * 알림 수신 스레드 시작
     *
     * <h3>Blocking 방식</h3>
     *
     * <p>PGConnection의 NOTIFIES는 Blocking Queue처럼 동작.
     * 별도 스레드에서 대기하다가 알림 도착 시 리스너 호출.
     */
    private fun startNotificationListener() {
        Thread {
            isListening = true
            log.info("[PubSub] Notification listener started")

            while (isListening) {
                try {
                    // PostgreSQL 알림 대기 (Blocking)
                    val notices = listenConnection.createStatement()
                        .executeQuery("SELECT 1")  // 더미 쿼리로 NOTIFIES 확인

                    // PGConnection에서 NOTIFIES 가져오기
                    val pgConn = listenConnection.unwrap(org.postgresql.PGConnection::class.java)
                    val notifications = Iterator {
                        // PGNotification 배열을 순회하는 Iterator
                        pgConn.notifications.asList().iterator()
                    }

                    while (notifications.hasNext()) {
                        val notification = notifications.next()
                        handleNotification(notification.name, notification.parameter)
                    }

                    Thread.sleep(100)  // Polling 간격
                } catch (e: Exception) {
                    if (isListening) {
                        log.warn("[PubSub] Notification listener error, reconnecting", e)
                        reconnect()
                    }
                }
            }
        }.apply {
            name = "postgres-pubsub-listener"
            isDaemon = true
            start()
        }
    }

    /**
     * 알림 핸들링
     */
    private fun handleNotification(channel: String, payload: String) {
        log.debug("[PubSub] Received: channel={}, payload={}", channel, payload)

        val channelListeners = listeners[channel]
        channelListeners?.forEach { callback ->
            try {
                callback(payload)
            } catch (e: Exception) {
                log.error("[PubSub] Listener callback error: channel={}", channel, e)
            }
        }
    }

    /**
     * 재연결
     */
    private fun reconnect() {
        log.warn("[PubSub] Reconnecting...")

        try {
            listenConnection.close()
        } catch (e: Exception) {
            log.error("[PubSub] Failed to close old connection", e)
        }

        // 새 연결 생성
        @Suppress("TooGenericExceptionCaught")
        val newConn = try {
            createListenConnection()
        } catch (e: Exception) {
            log.error("[PubSub] Failed to create new connection, retrying in 5s", e)
            Thread.sleep(5000)
            return reconnect()
        }

        // 기존 LISTEN 재등록
        listeners.keys.forEach { channel ->
            executeListen(channel)
        }

        log.info("[PubSub] Reconnected, listening to {} channels", listeners.size)
    }

    /**
     * 종료
     */
    @PreDestroy
    fun destroy() {
        log.info("[PubSub] Shutting down...")
        isListening = false

        try {
            executeUnlisten("*")
            listenConnection.close()
        } catch (e: Exception) {
            log.error("[PubSub] Error during shutdown", e)
        }
    }
}
```

### TieredCache 통합

```kotlin
// TieredCache에서 PostgreSQL Pub/Sub 사용
@Component
class CacheInvalidationPublisher(
    private val pubSubContainer: PostgresPubSubContainer,
    private val instanceIdSupplier: Supplier<String>
) : Consumer<CacheInvalidationEvent> {

    override fun accept(event: CacheInvalidationEvent) {
        when (event.type) {
            CacheInvalidationEvent.Type.EVICT -> {
                val channel = "cache_evict_${event.cacheName}"
                pubSubContainer.publish(channel, event.key ?: "")
            }
            CacheInvalidationEvent.Type.CLEAR_ALL -> {
                val channel = "cache_clear_${event.cacheName}"
                pubSubContainer.publish(channel, "*")
            }
        }
    }
}

@Component
class CacheInvalidationListener(
    private val pubSubContainer: PostgresPubSubContainer,
    private val cacheManager: CacheManager
) {

    init {
        // equipment_expectation 캐시 구독
        pubSubContainer.subscribe("cache_evict_equipment_expectation") { payload ->
            val cache = cacheManager.getCache("equipment_expectation")
            cache?.evict(payload)
        }

        pubSubContainer.subscribe("cache_clear_equipment_expectation") {
            val cache = cacheManager.getCache("equipment_expectation")
            cache?.clear()
        }
    }
}
```

---

## 5. 트레이드오프 (Trade-offs)

### ✅ 장점

| 항목 | 설명 |
|------|------|
| **PostgreSQL 네이티브** | 추가 인프라 불필요 |
| **트랜잭션 통합** | 트랜잭션 커밋 후 전송 보장 |
| **안정성** | DB 연결만 유지하면 됨 |
| **단일 연결** | DB 연결 하나로 해결 |

### ⚠️ 단점

| 항목 | 완화 방안 |
|------|----------|
| **8KB 페이로드 제한** | 참조 ID만 전달 |
| **와일드카드 미지원** | 채널명 구체화 |
| **별도 LISTEN 연결** | 전용 연결 관리 |
| **재연결 복잡성** | 자동 재연결 로직 |

---

## 6. 성능 비교

### Pub/Sub 성능

| 작업 | Redis | PostgreSQL LISTEN/NOTIFY |
|------|-------|--------------------------|
| 발행 지연 | ~1ms | ~2-5ms |
| 전파 지연 (LAN) | ~5ms | ~10-20ms |
| 페이로드 제한 | 없음 | 8KB |

### 경합 상황

| 구독자 수 | Redis | PostgreSQL |
|----------|-------|-----------|
| 1 | ~1ms | ~2ms |
| 10 | ~5ms | ~20ms |
| 100 | ~50ms | ~200ms |

---

## 7. 마이그레이션 계획

### Phase 1: PostgresPubSubContainer 구현

- [ ] PostgresPubSubContainer 기본 구현
- [ ] LISTEN 전용 연결 관리
- [ ] Reconnect 로직 구현
- [ ] 단위 테스트 작성

### Phase 2: TieredCache 통합

- [ ] CacheInvalidationPublisher → PostgreSQL 버전
- [ ] CacheInvalidationListener → PostgreSQL 버전
- [ ] Redis Pub/Sub 제거

### Phase 3: 검증

- [ ] 다중 인스턴스 테스트
- [ ] Reconnect 테스트
- [ ] 부하 테스트

---

## 8. 롤백 전략

### 롤백 트리거

| 조건 | 조치 |
|------|------|
| 전파 지연 > 1초 p99 | Redis Pub/Sub 복원 |
| 알림 손실 발생 | Redis Pub/Sub 복원 |
| LISTEN 연결 불안정 | Redis Pub/Sub 복원 |

### 롤백 절차

1. PostgresPubSubContainer 종료
2. RedisMessageListenerContainer 재활성화
3. 기능 플래그로 트래픽 전환

---

## 9. 모니터링 & 검증

### 성공 지표

| 지표 | 목표 |
|------|------|
| 발행 지연 p99 | < 50ms |
| 전파 지연 p99 | < 500ms |
| 알림 손실률 | 0% |
| LISTEN 연결 유지율 | > 99.9% |

### 모니터링 쿼리

```sql
-- 현재 LISTEN 상태
SELECT channel, count(*) as listener_count
FROM pg_listening_channels()
GROUP BY channel;

-- NOTIFY 통계
SELECT
    channel,
    count(*) as notification_count
FROM pg_notification_queue
GROUP BY channel;
```

---

## 10. 참고 자료

- [PostgreSQL LISTEN/NOTIFY](https://www.postgresql.org/docs/current/sql-notify.html)
- [PostgreSQL pg_listening_channels](https://www.postgresql.org/docs/current/functions-info.html)
- [ADR-001 PostgreSQL 단일 DB 전략](001-postgresql-single-db-strategy.md)
- [ADR-003 Redis 기능 PostgreSQL 대체](003-postgresql-redis-replacement.md)

---

## 11. 변경 이력

| 날짜 | 변경 내용 | 작성자 |
|------|----------|--------|
| 2026-03-10 | ADR 초안 작성 | probabilistic-valuation-engine Team |
