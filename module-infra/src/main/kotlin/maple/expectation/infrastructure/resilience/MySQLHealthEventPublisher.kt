package maple.expectation.infrastructure.resilience

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent
import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.infrastructure.event.MySQLDownEvent
import maple.expectation.infrastructure.event.MySQLUpEvent
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.redisson.api.RBucket
import org.redisson.api.RedissonClient
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import jakarta.annotation.PostConstruct

@Service
class MySQLHealthEventPublisher(
    private val circuitBreakerRegistry: CircuitBreakerRegistry,
    private val eventPublisher: ApplicationEventPublisher,
    private val redissonClient: RedissonClient,
    private val properties: MySQLFallbackProperties,
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry
) {
    private val logger = LoggerFactory.getLogger(MySQLHealthEventPublisher::class.java)

    companion object {
        private const val LIKE_SYNC_DB_CB = "likeSyncDb"
        private const val DOWN_TIMESTAMP_SUFFIX = ":down:timestamp"
    }

    @PostConstruct
    fun registerCircuitBreakerListener() {
        executor.executeVoidJava(
            {
                val likeSyncDbCb = circuitBreakerRegistry.circuitBreaker(LIKE_SYNC_DB_CB)
                likeSyncDbCb.eventPublisher.onStateTransition { handleStateTransition(it) }
                logger.info("[MySQLHealth] CircuitBreaker 리스너 등록 완료: $LIKE_SYNC_DB_CB")
            },
            TaskContext.of("Resilience", "RegisterCBListener", LIKE_SYNC_DB_CB)
        )
    }

    private fun handleStateTransition(event: CircuitBreakerOnStateTransitionEvent) {
        val fromState = event.stateTransition.fromState.name
        val toState = event.stateTransition.toState.name

        logger.info("[MySQLHealth] CB 상태 전이 감지: $fromState -> $toState")

        if ("OPEN" == toState) {
            handleCircuitBreakerOpen(fromState, toState)
        } else if ("CLOSED" == toState) {
            handleCircuitBreakerClosed(fromState, toState)
        }
    }

    private fun handleCircuitBreakerOpen(fromState: String, toState: String) {
        executor.executeVoidJava(
            {
                val currentState = getCurrentState()

                if (currentState.isDegraded()) {
                    logger.debug("[MySQLHealth] 이미 DEGRADED 상태, DOWN 이벤트 무시")
                    incrementFlappingIgnored()
                    return@executeVoidJava
                }

                saveDownTimestamp()
                scheduleDownEventAfterDebounce(fromState, toState)
            },
            TaskContext.of("Resilience", "HandleCBOpen", LIKE_SYNC_DB_CB)
        )
    }

    @Async
    fun scheduleDownEventAfterDebounce(fromState: String, toState: String) {
        executor.executeVoidJava(
            {
                try {
                    TimeUnit.SECONDS.sleep(properties.debounceSeconds.toLong())
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    logger.info("[MySQLHealth] Debounce sleep interrupted")
                    return@executeVoidJava
                }

                if (!isDownTimestampValid()) {
                    logger.info("[MySQLHealth] Debounce 중 UP 이벤트 발생 - DOWN 무시 (Flapping)")
                    incrementFlappingIgnored()
                    return@executeVoidJava
                }

                updateState(MySQLHealthState.DEGRADED)
                clearDownTimestamp()

                val event = MySQLDownEvent.of(LIKE_SYNC_DB_CB, fromState, toState)
                eventPublisher.publishEvent(event)

                logger.warn("[MySQLHealth] MySQL DOWN 이벤트 발행: $fromState -> $toState")
                incrementStateTransition()
                sendDiscordAlert("MySQL DOWN", fromState, toState)
            },
            TaskContext.of("Resilience", "PublishDownEvent", LIKE_SYNC_DB_CB)
        )
    }

    private fun handleCircuitBreakerClosed(fromState: String, toState: String) {
        executor.executeVoidJava(
            {
                val currentState = getCurrentState()

                clearDownTimestamp()

                if (currentState.isHealthy()) {
                    logger.debug("[MySQLHealth] 이미 HEALTHY 상태, UP 이벤트 무시")
                    return@executeVoidJava
                }

                val newState = currentState.onCircuitBreakerClosed()
                updateState(newState)

                val event = MySQLUpEvent.of(LIKE_SYNC_DB_CB, fromState, toState)
                eventPublisher.publishEvent(event)

                logger.info("[MySQLHealth] MySQL UP 이벤트 발행: $fromState -> $toState")
                incrementStateTransition()
                sendDiscordAlert("MySQL RECOVERING", fromState, toState)
            },
            TaskContext.of("Resilience", "PublishUpEvent", LIKE_SYNC_DB_CB)
        )
    }

    fun markRecoveryComplete() {
        executor.executeVoidJava(
            {
                val currentState = getCurrentState()
                if (currentState != MySQLHealthState.RECOVERING) {
                    logger.debug("[MySQLHealth] RECOVERING 상태가 아님, 복구 완료 무시: $currentState")
                    return@executeVoidJava
                }

                val newState = currentState.onRecoveryComplete()
                updateState(newState)
                logger.info("[MySQLHealth] 복구 완료: RECOVERING -> HEALTHY")
                incrementStateTransition()
            },
            TaskContext.of("Resilience", "MarkRecoveryComplete", LIKE_SYNC_DB_CB)
        )
    }

    fun getCurrentState(): MySQLHealthState {
        return executor.executeOrDefault(
            {
                val bucket: RBucket<String> = redissonClient.getBucket(properties.stateKey)
                val stateStr = bucket.get()
                stateStr?.let { MySQLHealthState.valueOf(it) } ?: MySQLHealthState.HEALTHY
            },
            MySQLHealthState.HEALTHY,
            TaskContext.of("Resilience", "GetState", properties.stateKey)
        )
    }

    private fun updateState(state: MySQLHealthState) {
        val bucket: RBucket<String> = redissonClient.getBucket(properties.stateKey)
        bucket.set(state.name, Duration.ofSeconds(properties.stateTtlSeconds.toLong()))
        logger.debug("[MySQLHealth] 상태 업데이트: {} (TTL: {}s)", state, properties.stateTtlSeconds)
    }

    private fun saveDownTimestamp() {
        val key = properties.stateKey + DOWN_TIMESTAMP_SUFFIX
        val bucket: RBucket<Long> = redissonClient.getBucket(key)
        bucket.set(Instant.now().toEpochMilli(), Duration.ofSeconds(properties.stateTtlSeconds.toLong()))
    }

    private fun isDownTimestampValid(): Boolean {
        val key = properties.stateKey + DOWN_TIMESTAMP_SUFFIX
        val bucket: RBucket<Long> = redissonClient.getBucket(key)
        return bucket.isExists
    }

    private fun clearDownTimestamp() {
        val key = properties.stateKey + DOWN_TIMESTAMP_SUFFIX
        redissonClient.getBucket<Any>(key).delete()
    }

    private fun incrementStateTransition() {
        meterRegistry.counter("mysql.state.transition").increment()
    }

    private fun incrementFlappingIgnored() {
        meterRegistry.counter("mysql.flapping.ignored").increment()
    }

    private fun sendDiscordAlert(event: String, fromState: String, toState: String) {
        val title = "🚨 $event 감지"
        val description = "CircuitBreaker: $LIKE_SYNC_DB_CB\nTransition: $fromState -> $toState\nTimestamp: ${Instant.now()}"
        logger.warn("[MySQLHealth] $title - $description")
    }
}
