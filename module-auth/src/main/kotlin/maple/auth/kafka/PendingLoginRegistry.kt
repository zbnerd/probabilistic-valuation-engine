package maple.auth.kafka

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import maple.expectation.core.auth.event.CharacterFetchResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class PendingLoginRegistry {
    private val pending = ConcurrentHashMap<String, CompletableFuture<CharacterFetchResponse>>()

    fun register(eventId: String): CompletableFuture<CharacterFetchResponse> {
        val future = CompletableFuture<CharacterFetchResponse>()
        pending[eventId] = future
        future.orTimeout(30, TimeUnit.SECONDS)
            .whenComplete { _, _ -> pending.remove(eventId) }
        log.debug("[PendingLogin] registered: eventId={}, pendingCount={}", eventId, pending.size)
        return future
    }

    fun complete(response: CharacterFetchResponse) {
        val future = pending.remove(response.eventId)
        if (future != null) {
            future.complete(response)
            log.debug("[PendingLogin] completed: eventId={}", response.eventId)
        } else {
            log.warn("[PendingLogin] no pending request for eventId={}", response.eventId)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(PendingLoginRegistry::class.java)
    }
}
