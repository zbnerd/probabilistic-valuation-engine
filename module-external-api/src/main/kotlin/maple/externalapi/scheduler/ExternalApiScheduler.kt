package maple.externalapi.scheduler

import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider
import maple.externalapi.port.inbound.FetchExternalApiUseCase
import maple.externalapi.reader.UserIgnCsvReader
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class ExternalApiScheduler(
    private val fetchUseCase: FetchExternalApiUseCase,
    private val csvReader: UserIgnCsvReader,
    @Value("\${external-api.rate-limit.permits-per-second:200}")
    private val permitsPerSecond: Int,
    @Value("\${external-api.batch-size:1000}")
    private val batchSize: Int,
) {
    private val log = LoggerFactory.getLogger(ExternalApiScheduler::class.java)

    private val running = AtomicBoolean(false)

    fun triggerOcidLookup() {
        if (!running.compareAndSet(false, true)) {
            log.warn("[Scheduler] already running, skipping")
            return
        }
        try {
            doOcidLookup()
        } finally {
            running.set(false)
        }
    }

    private fun doOcidLookup() {
        val rateLimiter = Bucket.builder()
            .addLimit(
                Bandwidth.builder()
                    .capacity(permitsPerSecond.toLong())
                    .refillIntervally(permitsPerSecond.toLong(), Duration.ofSeconds(1))
                    .build(),
            )
            .build()

        val igns = csvReader.readAll()
        if (igns.isEmpty()) {
            log.warn("[Scheduler] no IGNs to process")
            return
        }

        log.info("[Scheduler] OCID lookup start: total={}, rate={}/s, batchSize={}", igns.size, permitsPerSecond, batchSize)
        val start = Instant.now()
        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)

        igns.chunked(batchSize).forEach { chunk ->
            chunk.forEach { ign ->
                while (!rateLimiter.tryConsume(1)) {
                    Thread.sleep(5)
                }
                val result = fetchUseCase.fetchSingle(
                    provider = ExternalApiProvider.NEXON,
                    endpoint = ExternalApiEndpoint.OCID_LOOKUP,
                    requestKey = ign,
                    characterName = ign,
                )
                if (result.success) successCount.incrementAndGet() else failCount.incrementAndGet()
            }
            val progress = successCount.get() + failCount.get()
            if (progress % 10000 == 0) {
                log.info("[Scheduler] progress: {}/{} (success={}, fail={})", progress, igns.size, successCount.get(), failCount.get())
            }
        }

        val elapsed = Duration.between(start, Instant.now())
        log.info(
            "[Scheduler] OCID lookup done: total={}, success={}, fail={}, elapsed={}s",
            igns.size,
            successCount.get(),
            failCount.get(),
            elapsed.seconds,
        )
    }
}
