package maple.expectation.infrastructure.scheduler

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.annotation.PostConstruct
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import maple.expectation.core.port.out.CacheWarmupPort
import maple.expectation.core.port.out.PopularCharacterTrackerPort
import maple.expectation.error.exception.DistributedLockException
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.lock.LockStrategy
import maple.expectation.util.StringMaskingUtils.maskIgn
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 인기 캐릭터 자동 웜업 스케줄러 (ADR-005 이관)
 */
@Component
@ConditionalOnProperty(
    name = ["scheduler.warmup.enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
class PopularCharacterWarmupScheduler(
    private val popularCharacterTracker: PopularCharacterTrackerPort,
    private val cacheWarmupPort: CacheWarmupPort,
    private val lockStrategy: LockStrategy,
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(PopularCharacterWarmupScheduler::class.java)

    @Value("\${scheduler.warmup.top-count:50}")
    private var topCount: Int = 50

    @Value("\${scheduler.warmup.delay-between-ms:100}")
    private var delayBetweenMs: Long = 100

    private val successCount = AtomicInteger(0)
    private val failCount = AtomicInteger(0)

    @PostConstruct
    fun init() {
        meterRegistry.gauge("warmup.last.success_count", successCount)
        meterRegistry.gauge("warmup.last.fail_count", failCount)
    }

    @Scheduled(cron = "0 0 5 * * *")
    fun dailyWarmup() {
        executeWarmup("DailyWarmup")
    }

    @Scheduled(initialDelay = 30000, fixedDelay = Long.MAX_VALUE)
    fun initialWarmup() {
        executeWarmup("InitialWarmup")
    }

    private fun executeWarmup(warmupType: String) {
        val context = TaskContext.of("Scheduler", "Warmup.$warmupType")

        executor.executeOrCatch(
            {
                lockStrategy.executeWithLock(
                    "popular-warmup-lock",
                    0,
                    300,
                ) {
                    doWarmup(warmupType)
                    null
                }
                null
            },
            { e ->
                if (e is DistributedLockException) {
                    log.debug("[Warmup] {} skipped: another instance is warming up", warmupType)
                } else {
                    log.error("[Warmup] {} failed: {}", warmupType, e.message)
                    meterRegistry.counter("warmup.execution", "type", warmupType, "status", "error")
                        .increment()
                }
                null
            },
            context,
        )
    }

    private fun doWarmup(warmupType: String) {
        val sample = Timer.start(meterRegistry)
        log.info(
            "[Warmup] {} started at {} - warming up top {} characters",
            warmupType,
            LocalDateTime.now(),
            topCount,
        )

        val topCharacters = popularCharacterTracker.getYesterdayTopCharacters(topCount)

        if (topCharacters.isEmpty()) {
            log.info("[Warmup] {} completed - no characters to warm up (first day?)", warmupType)
            meterRegistry.counter("warmup.execution", "type", warmupType, "status", "empty")
                .increment()
            return
        }

        successCount.set(0)
        failCount.set(0)

        for (userIgn in topCharacters) {
            warmupCharacter(userIgn)
            if (delayBetweenMs > 0) {
                sleep(delayBetweenMs)
            }
        }

        sample.stop(meterRegistry.timer("warmup.duration", "type", warmupType))
        meterRegistry.counter("warmup.execution", "type", warmupType, "status", "success")
            .increment()

        log.info(
            "[Warmup] {} completed - success: {}, fail: {}, total: {}",
            warmupType,
            successCount.get(),
            failCount.get(),
            topCharacters.size,
        )
    }

    private fun warmupCharacter(userIgn: String) {
        executor.executeOrCatch(
            {
                cacheWarmupPort.warmup(userIgn, false)
                successCount.incrementAndGet()
                log.debug("[Warmup] Warmed up: {}", maskIgn(userIgn))
                null
            },
            { e ->
                failCount.incrementAndGet()
                log.warn("[Warmup] Failed to warm up {}: {}", maskIgn(userIgn), e.message)
                null
            },
            TaskContext.of("Warmup", "Character", userIgn),
        )
    }

    private fun sleep(millis: Long) {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(millis))
    }
}
