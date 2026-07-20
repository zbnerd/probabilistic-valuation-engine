package maple.externalapi.scheduler

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["external-api.schedule.enabled"], havingValue = "true")
class ExternalApiSchedulerLifecycle(
    private val scheduler: ExternalApiScheduler,
) : SmartLifecycle, ApplicationListener<ApplicationReadyEvent> {
    private val running = AtomicBoolean(false)
    private val ready = AtomicBoolean(false)
    private val stopStarted = AtomicBoolean(false)
    private val stopCompletion = CompletableFuture<Void>()

    override fun start() {
        running.set(true)
    }

    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        if (ready.compareAndSet(false, true)) scheduler.startAfterReady()
    }

    override fun stop() {
        stop(Runnable {})
    }

    override fun stop(callback: Runnable) {
        running.set(false)
        stopCompletion.whenComplete { _, _ -> callback.run() }
        if (stopStarted.compareAndSet(false, true)) {
            Thread.ofVirtual().name("external-api-scheduler-stop").start {
                runCatching { scheduler.stopAndAwait(SHUTDOWN_TIMEOUT) }
                    .onSuccess { stopCompletion.complete(null) }
                    .onFailure { failure ->
                        log.warn("External API scheduler lifecycle stop failed", failure)
                        stopCompletion.completeExceptionally(failure)
                    }
            }
        }
    }

    override fun isRunning(): Boolean = running.get()

    override fun isAutoStartup(): Boolean = true

    override fun getPhase(): Int = Int.MAX_VALUE - 100

    private companion object {
        private val SHUTDOWN_TIMEOUT: Duration = Duration.ofSeconds(5)
        private val log = LoggerFactory.getLogger(ExternalApiSchedulerLifecycle::class.java)
    }
}
