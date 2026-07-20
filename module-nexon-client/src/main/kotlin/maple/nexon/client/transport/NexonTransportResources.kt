package maple.nexon.client.transport

import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import maple.nexon.client.metrics.NexonClientMetrics
import org.springframework.context.SmartLifecycle
import reactor.core.publisher.Mono
import reactor.netty.resources.ConnectionProvider

class NexonTransportResources(
    systemProvider: ConnectionProvider,
    byokProvider: ConnectionProvider,
    private val shutdownTimeout: Duration = Duration.ofSeconds(5),
    private val metrics: NexonClientMetrics,
) : SmartLifecycle {
    private val ownedProviders = AtomicReference(listOf(systemProvider, byokProvider))
    private val running = AtomicBoolean()
    private val stopCompletion = AtomicReference<Mono<Void>?>()

    init {
        require(!shutdownTimeout.isZero && !shutdownTimeout.isNegative) {
            "Nexon provider shutdown timeout must be positive"
        }
    }

    override fun start() {
        running.set(true)
    }

    override fun stop() {
        stop(Runnable {})
    }

    override fun stop(callback: Runnable) {
        val callbackInvoked = AtomicBoolean()
        stopOperation().doFinally {
            if (callbackInvoked.compareAndSet(false, true)) {
                callback.run()
            }
        }.subscribe()
    }

    override fun isRunning(): Boolean = running.get()

    override fun isAutoStartup(): Boolean = true

    override fun getPhase(): Int = 0

    private fun stopOperation(): Mono<Void> {
        stopCompletion.get()?.let { return it }
        val created = Mono.defer {
            val providers = ownedProviders.getAndSet(emptyList())
            if (providers.isEmpty()) {
                Mono.empty()
            } else {
                Mono.whenDelayError(providers.map(ConnectionProvider::disposeLater))
            }
        }.timeout(shutdownTimeout)
            .doOnError { metrics.recordResourceDisposalFailure() }
            .onErrorResume { Mono.empty() }
            .doFinally { running.set(false) }
            .cache()
        return if (stopCompletion.compareAndSet(null, created)) created else requireNotNull(stopCompletion.get())
    }
}
