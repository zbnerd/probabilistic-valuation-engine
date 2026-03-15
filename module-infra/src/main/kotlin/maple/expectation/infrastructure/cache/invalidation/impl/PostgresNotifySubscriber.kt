package maple.expectation.infrastructure.cache.invalidation.impl

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PreDestroy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource
import maple.expectation.infrastructure.cache.TieredCacheManager
import maple.expectation.infrastructure.cache.invalidation.CacheInvalidationEvent
import maple.expectation.infrastructure.cache.invalidation.CacheInvalidationSubscriber
import maple.expectation.infrastructure.cache.invalidation.InvalidationType
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.postgresql.PGConnection
import org.postgresql.PGNotification
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * PostgreSQL LISTEN/NOTIFY 기반 캐시 무효화 이벤트 구독자
 *
 * <h3>Issue #278: Scale-out 환경 L1 Cache Coherence</h3>
 *
 * <p>PostgreSQL LISTEN을 사용하여 다른 인스턴스의 캐시 무효화 이벤트 수신
 *
 * <h3>동작 방식</h3>
 *
 * <ul>
 *   <li>전용 Connection 사용 (Pool에서 분리)</li>
 *   <li>LISTEN on startup (모든 캐시 이름에 대해)</li>
 *   <li>Background thread로 getNotifications() 폴링</li>
 *   <li>연결 실패 시 재연결 전략</li>
 * </ul>
 *
 * <h3>P0-3 반영: TieredCacheManager 직접 주입</h3>
 *
 * <p>getL1CacheDirect()로 L1 캐시만 직접 접근 (L2 evict 불필요)
 *
 * <h3>캐시 무효화 전략</h3>
 *
 * <ul>
 *   <li>EVICT: 특정 키의 L1 캐시만 무효화</li>
 *   <li>CLEAR_ALL: 해당 캐시의 L1 전체 무효화</li>
 *   <li>Self-skip: 자기 자신이 발행한 이벤트는 무시</li>
 * </ul>
 *
 * <h3>CLAUDE.md Section 12: LogicExecutor 패턴</h3>
 *
 * <p>모든 캐시 작업은 executeVoid로 예외 처리
 *
 * @see PostgresNotifyPublisher 발행자 구현
 */
@Component
class PostgresNotifySubscriber(
    private val dataSource: DataSource,
    private val tieredCacheManager: TieredCacheManager?,
    private val objectMapper: ObjectMapper,
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry,
    @Value("\${app.instance-id:\${HOSTNAME:unknown}}") private val instanceId: String,
) : CacheInvalidationSubscriber {
    companion object {
        private val log = LoggerFactory.getLogger(PostgresNotifySubscriber::class.java)
        private const val CHANNEL_PREFIX = "cache_invalidation_"
        private const val POLL_INTERVAL_MS = 100L
        private const val RECONNECT_DELAY_MS = 5000L
    }

    @Volatile
    private var listenConnection: java.sql.Connection? = null

    @Volatile
    private var pgConnection: PGConnection? = null

    private val running = AtomicBoolean(false)

    /** 이벤트 구독 시작 (애플리케이션 시작 시) */
    override fun subscribe() {
        val context = TaskContext.of("CacheInvalidation", "PostgresSubscribe", instanceId)

        executor.executeVoid({
            startListening()
        }, context)
    }

    /** LISTEN 시작 및 백그라운드 스레드 실행 */
    private fun startListening() {
        if (running.compareAndSet(false, true)) {
            establishConnection()
            startNotificationListener()

            log.info(
                "[PostgresNotify] Subscribed to PostgreSQL NOTIFY: instanceId={}",
                instanceId,
            )
        }
    }

    /** 전용 Connection 생성 및 LISTEN 등록 */
    private fun establishConnection() {
        val context = TaskContext.of("CacheInvalidation", "EstablishConnection", instanceId)

        executor.executeVoid({
            // Close existing connection if any
            closeConnectionInternal()

            // Create new dedicated connection
            val conn = dataSource.getConnection()
            listenConnection = conn

            // Unwrap to PGConnection for LISTEN/NOTIFY support
            val pgConn = conn.unwrap(PGConnection::class.java)
            pgConnection = pgConn

            // LISTEN on all cache channels (wildcard not supported, listen on prefix)
            // PostgreSQL doesn't support wildcard LISTEN, so we listen to a base channel
            // Individual cache invalidations will use cache-specific channels
            conn.createStatement().use { stmt ->
                stmt.execute("LISTEN cache_invalidation")
            }

            log.debug("[PostgresNotify] Established LISTEN connection")
        }, context)
    }

    /** 알림 수신을 위한 백그라운드 스레드 시작 */
    private fun startNotificationListener() {
        val thread = Thread(this::runNotificationListener, "postgres-notify-listener").apply {
            isDaemon = true
        }
        thread.start()
    }

    /** 알림 수신 루프 */
    private fun runNotificationListener() {
        while (running.get()) {
            try {
                val pgConn = pgConnection
                if (pgConn != null) {
                    // Poll for notifications (non-blocking)
                    val notifications = pgConn.notifications

                    if (notifications != null) {
                        for (notification in notifications) {
                            handleNotification(notification)
                        }
                    }
                }

                // Short sleep to prevent busy waiting
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                log.warn("[PostgresNotify] Error receiving notifications, reconnecting...", e)
                reconnectWithDelay()
            }
        }
    }

    /** 수신된 알림 처리 */
    private fun handleNotification(notification: PGNotification) {
        val context = TaskContext.of("CacheInvalidation", "HandleNotification", notification.name)

        executor.executeVoid({
            val payload = notification.parameter
            if (payload.isBlank()) {
                log.debug("[PostgresNotify] Received empty notification: channel={}", notification.name)
                return@executeVoid
            }

            // Deserialize event
            val event = try {
                objectMapper.readValue(payload, CacheInvalidationEvent::class.java)
            } catch (e: Exception) {
                log.warn("[PostgresNotify] Failed to deserialize event: payload={}", payload, e)
                return@executeVoid
            }

            onEvent(event)
        }, context)
    }

    /**
     * 이벤트 수신 및 처리
     *
     * <p>Self-skip: 자기가 발행한 이벤트는 무시
     */
    override fun onEvent(event: CacheInvalidationEvent) {
        // Self-skip: 자기가 발행한 이벤트는 무시
        if (instanceId == event.sourceInstanceId) {
            log.trace("[PostgresNotify] Self-skip: cache={}, type={}", event.cacheName, event.type)
            return
        }

        val context = TaskContext.of("CacheInvalidation", "OnEvent", event.cacheName)

        executor.executeVoid({
            invalidateL1Cache(event)
            recordEventReceived(event.type)
        }, context)
    }

    /**
     * L1 캐시 무효화 (P0-3: TieredCacheManager.getL1CacheDirect() 사용)
     *
     * <p>L2(PostgreSQL)는 모든 인스턴스가 공유하므로 evict 불필요. L1(Caffeine)만 직접 무효화하여 Cache Coherence 보장.
     */
    private fun invalidateL1Cache(event: CacheInvalidationEvent) {
        if (tieredCacheManager == null) {
            log.warn("[PostgresNotify] TieredCacheManager is null, skipping L1 invalidation")
            return
        }

        val l1Cache = tieredCacheManager.getL1CacheDirect(event.cacheName)
        if (l1Cache == null) {
            log.debug("[PostgresNotify] L1 cache not found: {}", event.cacheName)
            return
        }

        when (event.type) {
            InvalidationType.EVICT -> {
                event.key?.let { l1Cache.evict(it) }
                log.debug(
                    "[PostgresNotify] L1 evicted: cache={}, key={}, source={}",
                    event.cacheName,
                    event.key,
                    event.sourceInstanceId,
                )
            }
            InvalidationType.CLEAR_ALL -> {
                l1Cache.clear()
                log.debug(
                    "[PostgresNotify] L1 cleared: cache={}, source={}",
                    event.cacheName,
                    event.sourceInstanceId,
                )
            }
        }
    }

    /** 재연결 (지연 후) */
    private fun reconnectWithDelay() {
        val context = TaskContext.of("CacheInvalidation", "Reconnect", instanceId)

        executor.executeVoid({
            closeConnectionInternal()
            TimeUnit.MILLISECONDS.sleep(RECONNECT_DELAY_MS)
            if (running.get()) {
                establishConnection()
            }
        }, context)
    }

    /** 구독 해제 (애플리케이션 종료 시) */
    @PreDestroy
    override fun unsubscribe() {
        val context = TaskContext.of("CacheInvalidation", "PostgresUnsubscribe", instanceId)

        executor.executeVoid({
            running.set(false)
            closeConnectionInternal()

            log.info("[PostgresNotify] Unsubscribed from PostgreSQL NOTIFY: instanceId={}", instanceId)
        }, context)
    }

    /** Connection 정리 */
    private fun closeConnectionInternal() {
        try {
            (pgConnection as? java.sql.Connection)?.close()
        } catch (e: Exception) {
            log.trace("[PostgresNotify] Error closing PGConnection", e)
        }
        try {
            listenConnection?.close()
        } catch (e: Exception) {
            log.trace("[PostgresNotify] Error closing Connection", e)
        }
        pgConnection = null
        listenConnection = null
    }

    // ==================== Metrics ====================

    private fun recordEventReceived(type: InvalidationType) {
        meterRegistry.counter("cache.invalidation.received", "impl", "postgres", "type", type.name).increment()
    }
}
