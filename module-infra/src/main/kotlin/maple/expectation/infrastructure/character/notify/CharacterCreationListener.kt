package maple.expectation.infrastructure.character.notify

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.postgresql.PGConnection
import org.postgresql.PGNotification
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Character Creation Event Listener
 *
 * <p>PostgreSQL LISTEN을 사용하여 캐릭터 생성 이벤트를 수신하고 대기 중인 CompletableFuture를 완료합니다.
 *
 * <h3>Channel</h3>
 * character_creation:{userIgn}
 *
 * <h3>Usage</h3>
 * GameCharacterFacade에서 waitForCharacterCreation(userIgn) 호출하여
 * 비동기 대기 (Thread.sleep 제거)
 *
 * @see CharacterCreationNotifier
 */
@Component
class CharacterCreationListener(
    private val dataSource: DataSource,
    private val executor: LogicExecutor,
    @Value("\${app.character.creation.timeout-seconds:10}") private val timeoutSeconds: Long,
) {
    companion object {
        private val log = LoggerFactory.getLogger(CharacterCreationListener::class.java)
        private const val POLL_INTERVAL_MS = 100L
        private const val CHANNEL_PREFIX = "character_creation"
    }

    private data class PgConnections(
        val listen: java.sql.Connection,
        val pg: PGConnection,
    )

    private val connections = AtomicReference<PgConnections>(null)

    private val running = AtomicBoolean(false)
    private val waitingFutures = ConcurrentHashMap<String, CompletableFuture<String>>()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread.ofVirtual().name("character-creation-poller").unstarted(r)
    }

    @PostConstruct
    fun startListening() {
        if (running.compareAndSet(false, true)) {
            establishConnection()
            startNotificationListener()
            log.info("[CharacterCreationListener] Started listening")
        }
    }

    /** 전용 Connection 생성 및 LISTEN 준비 */
    private fun establishConnection() {
        val context = TaskContext.of("CharacterCreation", "EstablishConnection")

        executor.executeVoid({
            closeConnectionInternal()

            val conn = dataSource.getConnection()
            val pgConn = conn.unwrap(PGConnection::class.java)
            connections.set(PgConnections(conn, pgConn))

            log.debug("[CharacterCreationListener] Connection established")
        }, context)
    }

    /** 알림 수신을 위한 ScheduledExecutorService 시작 */
    private fun startNotificationListener() {
        scheduler.scheduleAtFixedRate(
            {
                executor.executeOrCatch(
                    {
                        pollNotifications()
                        null
                    },
                    { e ->
                        log.warn("[CharacterCreationListener] Error receiving notifications, reconnecting...", e)
                        reconnectWithDelay()
                        null
                    },
                    TaskContext.of("CharacterCreation", "PollNotifications"),
                )
            },
            0,
            POLL_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    /** 알림 폴링 (ScheduledExecutorService에서 호출) */
    private fun pollNotifications() {
        if (!running.get()) return

        val pgConn = connections.get()?.pg
        if (pgConn != null) {
            val notifications = pgConn.notifications

            if (notifications != null) {
                for (notification in notifications) {
                    handleNotification(notification)
                }
            }
        }
    }

    /** 수신된 알림 처리 - Future 완료 */
    private fun handleNotification(notification: PGNotification) {
        val channelName = notification.name
        if (!channelName.startsWith("$CHANNEL_PREFIX:")) {
            return
        }

        val userIgn = channelName.substringAfter("$CHANNEL_PREFIX:")
        val future = waitingFutures.remove(userIgn)

        if (future != null) {
            log.debug("[CharacterCreationListener] Completing future for: {}", userIgn)
            future.complete(userIgn)
        } else {
            log.trace("[CharacterCreationListener] No waiting future for: {}", userIgn)
        }
    }

    /**
     * 캐릭터 생성 대기 (Thread.sleep 대체)
     *
     * @param userIgn 캐릭터 닉네임
     * @return CompletableFuture - 완료 시 userIgn 반환
     */
    fun waitForCharacterCreation(userIgn: String): CompletableFuture<String> {
        val future = CompletableFuture<String>()
        waitingFutures[userIgn] = future

        // LISTEN on the specific channel
        val channel = "$CHANNEL_PREFIX:$userIgn"
        connections.get()?.listen?.createStatement()?.use { stmt ->
            stmt.execute("LISTEN \"$channel\"")
        }

        // Timeout 설정
        executor.executeVoid({
            future.completeOnTimeout(userIgn, timeoutSeconds, TimeUnit.SECONDS)
            waitingFutures.remove(userIgn)
        }, TaskContext.of("CharacterCreation", "TimeoutSchedule", userIgn))

        log.debug("[CharacterCreationListener] Waiting for character creation: {}", userIgn)
        return future
    }

    private fun reconnectWithDelay() {
        val context = TaskContext.of("CharacterCreation", "Reconnect")

        closeConnectionInternal()

        scheduler.schedule({
            if (running.get()) {
                executor.executeVoid({
                    establishConnection()
                }, context)
            }
        }, 1000, TimeUnit.MILLISECONDS)
    }

    @PreDestroy
    fun stopListening() {
        running.set(false)
        scheduler.shutdown()
        if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
            log.warn("[CharacterCreationListener] Scheduler did not terminate gracefully, forcing shutdown")
            scheduler.shutdownNow()
        }
        closeConnectionInternal()
        log.info("[CharacterCreationListener] Stopped listening")
    }

    private fun closeConnectionInternal() {
        val conns = connections.getAndSet(null)
        if (conns != null) {
            executor.executeVoid({
                conns.listen.close()
            }, TaskContext.of("CharacterCreation", "CloseConnection"))
        }
    }
}
