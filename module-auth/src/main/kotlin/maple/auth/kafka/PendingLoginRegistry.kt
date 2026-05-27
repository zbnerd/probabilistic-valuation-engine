package maple.auth.kafka

import maple.expectation.core.auth.event.CharacterFetchResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Component
class PendingLoginRegistry {
    private val pending = ConcurrentHashMap<String, CompletableFuture<CharacterFetchResponse>>()

    fun register(fingerprint: String): CompletableFuture<CharacterFetchResponse> {
        val future = CompletableFuture<CharacterFetchResponse>()
        pending[fingerprint] = future
        future.orTimeout(30, TimeUnit.SECONDS)
            .whenComplete { _, _ -> pending.remove(fingerprint) }
        log.debug("[PendingLogin] registered: fingerprint={}, pendingCount={}", fingerprint, pending.size)
        return future
    }

    fun complete(response: CharacterFetchResponse) {
        val future = pending.remove(response.fingerprint)
        if (future != null) {
            future.complete(response)
            log.debug("[PendingLogin] completed: fingerprint={}", response.fingerprint)
        } else {
            log.warn("[PendingLogin] no pending request for fingerprint={}", response.fingerprint)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(PendingLoginRegistry::class.java)
    }
}
