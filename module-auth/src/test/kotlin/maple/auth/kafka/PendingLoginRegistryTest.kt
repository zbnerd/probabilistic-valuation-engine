package maple.auth.kafka

import maple.expectation.core.auth.event.CharacterFetchResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class PendingLoginRegistryTest {
    private val registry = PendingLoginRegistry()

    @Test
    fun `complete resolves the future with matching response`() {
        val future = registry.register("fp-1")
        val response = CharacterFetchResponse(
            eventId = "evt-1",
            fingerprint = "fp-1",
            success = true,
            characterOcidMap = mapOf("Char1" to "ocid-1"),
        )
        registry.complete(response)
        val result = future.get(1, TimeUnit.SECONDS)
        assertThat(result.success).isTrue()
        assertThat(result.characterOcidMap).containsEntry("Char1", "ocid-1")
    }

    @Test
    fun `unregistered fingerprint complete does not throw`() {
        val response = CharacterFetchResponse(
            eventId = "evt-1",
            fingerprint = "unknown-fp",
            success = true,
        )
        registry.complete(response)
    }

    @Test
    fun `entry is removed after complete`() {
        val future = registry.register("fp-1")
        val response = CharacterFetchResponse(eventId = "evt-1", fingerprint = "fp-1", success = true)
        registry.complete(response)
        future.get(1, TimeUnit.SECONDS)

        // Second complete for same fingerprint should not throw (already removed)
        registry.complete(response)
    }

    @Test
    fun `register returns distinct futures for different fingerprints`() {
        val f1 = registry.register("fp-1")
        val f2 = registry.register("fp-2")

        registry.complete(CharacterFetchResponse(eventId = "e1", fingerprint = "fp-1", success = true, characterOcidMap = mapOf("A" to "o1")))
        registry.complete(CharacterFetchResponse(eventId = "e2", fingerprint = "fp-2", success = true, characterOcidMap = mapOf("B" to "o2")))

        assertThat(f1.get(1, TimeUnit.SECONDS).characterOcidMap).containsKey("A")
        assertThat(f2.get(1, TimeUnit.SECONDS).characterOcidMap).containsKey("B")
    }
}
