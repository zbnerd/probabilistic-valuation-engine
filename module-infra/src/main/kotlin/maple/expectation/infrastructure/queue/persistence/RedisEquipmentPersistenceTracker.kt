package maple.expectation.infrastructure.queue.persistence

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import maple.expectation.core.port.out.PersistenceTrackerStrategy
import maple.expectation.core.port.out.PersistenceTrackerStrategy.StrategyType
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.queue.RedisKey
import org.redisson.api.RSet
import org.redisson.api.RedissonClient
import org.slf4j.LoggerFactory

/**
 * Redis 기반 Equipment 비동기 저장 작업 추적기 (#271 V5 Stateless Architecture)
 */
class RedisEquipmentPersistenceTracker(
    private val redissonClient: RedissonClient,
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry,
) : PersistenceTrackerStrategy {

    private val trackingKey: String
    private val localFutures = ConcurrentHashMap<String, CompletableFuture<Void>>()
    private val shutdownInProgress = AtomicBoolean(false)

    init {
        this.trackingKey = RedisKey.PERSISTENCE_TRACKING.key
        registerMetrics()
        log.info("[RedisEquipmentPersistenceTracker] Initialized with key: {}", trackingKey)
    }

    private fun registerMetrics() {
        Gauge.builder("persistence.tracker.local.pending", localFutures) { it.size.toDouble() }
            .description("현재 인스턴스의 pending 작업 수")
            .register(meterRegistry)

        Gauge.builder("persistence.tracker.global.pending", this) { tracker ->
            tracker.executor.executeOrDefault(
                { tracker.trackingSet.size.toDouble() },
                0.0,
                TaskContext.of("PersistenceTracker", "GetGlobalCountMetric"),
            )
        }
            .description("전역 pending 작업 수 (모든 인스턴스)")
            .register(meterRegistry)
    }

    override fun trackOperation(ocid: String, future: CompletableFuture<Void>) {
        if (shutdownInProgress.get()) {
            meterRegistry.counter("persistence.tracker.rejected", "reason", "shutdown").increment()
            log.warn("[PersistenceTracker] Shutdown 진행 중 - 작업 거부: {}", ocid)
            throw IllegalStateException("Shutdown 진행 중에는 등록할 수 없습니다.")
        }

        addToRedisTracking(ocid)
        localFutures[ocid] = future

        future.whenComplete { result, throwable ->
            executor.executeVoid(
                {
                    removeFromRedisTracking(ocid)
                    localFutures.remove(ocid)

                    if (throwable != null) {
                        meterRegistry.counter("persistence.tracker.failed").increment()
                        log.error("[PersistenceTracker] 비동기 저장 실패: {}", ocid, throwable)
                        return@executeVoid
                    }

                    meterRegistry.counter("persistence.tracker.completed").increment()
                    log.debug("[PersistenceTracker] 비동기 저장 완료: {}", ocid)
                },
                TaskContext.of("PersistenceTracker", "CompleteOperation", ocid),
            )
        }

        meterRegistry.counter("persistence.tracker.registered").increment()
        log.debug("[PersistenceTracker] 작업 등록: {}", ocid)
    }

    override fun awaitAllCompletion(timeout: Duration): Boolean {
        if (!shutdownInProgress.compareAndSet(false, true)) {
            log.warn("[PersistenceTracker] Shutdown 이미 진행 중")
            return false
        }
        log.info("[PersistenceTracker] Shutdown 시작 - 새로운 작업 등록 차단")

        if (localFutures.isEmpty()) {
            log.info("[PersistenceTracker] 대기 중인 로컬 작업 없음")
            return true
        }

        val context = TaskContext.of("PersistenceTracker", "AwaitAll", localFutures.size.toString())

        return executor.executeWithFallback(
            {
                log.info("[PersistenceTracker] {}건 로컬 작업 대기 중... (timeout: {}s)", localFutures.size, timeout.seconds)

                CompletableFuture.allOf(*localFutures.values.toTypedArray())
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS)

                log.info("[PersistenceTracker] 모든 로컬 작업 완료")
                true
            },
            { e ->
                if (e is java.util.concurrent.TimeoutException) {
                    log.warn("[PersistenceTracker] Timeout 발생. 미완료 로컬 작업: {}건", localFutures.size)
                } else {
                    log.error("[PersistenceTracker] 작업 대기 중 예외 발생: {}", e.message)
                }
                false
            },
            context,
        )
    }

    override fun getPendingOcids(): java.util.List<String> = localFutures.keys.toList() as java.util.List<String>

    override fun getPendingCount(): Int = localFutures.size

    override fun getType(): StrategyType = StrategyType.REDIS

    private val trackingSet: RSet<String> get() = redissonClient.getSet(trackingKey)

    fun getGlobalPendingOcids(): List<String> = executor.executeOrDefault(
        {
            val tracking = trackingSet
            val members = tracking.readAll()
            members.toList()
        },
        emptyList(),
        TaskContext.of("PersistenceTracker", "GetGlobalPending"),
    )

    fun isGloballyPending(ocid: String): Boolean = executor.executeOrDefault(
        { trackingSet.contains(ocid) },
        false,
        TaskContext.of("PersistenceTracker", "IsPending", ocid),
    )

    override fun resetForTesting() {
        shutdownInProgress.set(false)
        localFutures.clear()

        executor.executeVoid(
            { trackingSet.clear() },
            TaskContext.of("PersistenceTracker", "ResetForTesting"),
        )

        log.debug("[PersistenceTracker] 테스트용 리셋 완료")
    }

    private fun addToRedisTracking(ocid: String) {
        executor.executeVoid(
            {
                trackingSet.add(ocid)
                log.debug("[PersistenceTracker] Redis 추적 등록: {}", ocid)
            },
            TaskContext.of("PersistenceTracker", "AddToRedis", ocid),
        )
    }

    private fun removeFromRedisTracking(ocid: String) {
        executor.executeVoid(
            {
                trackingSet.remove(ocid)
                log.debug("[PersistenceTracker] Redis 추적 제거: {}", ocid)
            },
            TaskContext.of("PersistenceTracker", "RemoveFromRedis", ocid),
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(RedisEquipmentPersistenceTracker::class.java)
    }
}
