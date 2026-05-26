package maple.restcontroller.read

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.ResponseEntity
import org.springframework.web.context.request.async.DeferredResult

class InflightRequestRegistryTest {

    private val registry = InflightRequestRegistry()

    private fun deferred(): DeferredResult<ResponseEntity<*>> =
        DeferredResult()

    @Test
    fun `register returns true for first request (dedup miss)`() {
        val result = registry.register("진격캐넌", 1, deferred())
        assertThat(result).isTrue
    }

    @Test
    fun `register returns false for duplicate userIgn and presetNo (dedup hit)`() {
        registry.register("진격캐넌", 1, deferred())
        val result = registry.register("진격캐넌", 1, deferred())
        assertThat(result).isFalse
    }

    @Test
    fun `register treats different presetNo as separate inflight request`() {
        registry.register("진격캐넌", 1, deferred())
        val result = registry.register("진격캐넌", 2, deferred())
        assertThat(result).isTrue
        assertThat(registry.size()).isEqualTo(2)
    }

    @Test
    fun `size returns unique userIgn and presetNo count`() {
        registry.register("a", 1, deferred())
        registry.register("b", 1, deferred())
        registry.register("a", 1, deferred())
        assertThat(registry.size()).isEqualTo(2)
    }

    @Test
    fun `getAndRemove returns all deferreds for userIgn`() {
        val d1 = deferred()
        val d2 = deferred()
        registry.register("진격캐넌", 1, d1)
        registry.register("진격캐넌", 1, d2)

        val removed = registry.getAndRemove("진격캐넌", 1)
        assertThat(removed).containsExactly(d1, d2)
        assertThat(registry.size()).isZero
    }

    @Test
    fun `getAndRemove for non-existent key returns empty list`() {
        assertThat(registry.getAndRemove("none", 1)).isEmpty()
    }

    @Test
    fun `cleanup removes specific deferred from list`() {
        val d1 = deferred()
        val d2 = deferred()
        registry.register("진격캐넌", 1, d1)
        registry.register("진격캐넌", 1, d2)

        registry.cleanup("진격캐넌", 1, d1)

        val remaining = registry.getAndRemove("진격캐넌", 1)
        assertThat(remaining).containsExactly(d2)
    }

    @Test
    fun `cleanup removes entry when list becomes empty`() {
        val d = deferred()
        registry.register("진격캐넌", 1, d)
        registry.cleanup("진격캐넌", 1, d)
        assertThat(registry.size()).isZero
    }

    @Test
    fun `failAll sets error on all pending deferreds and clears registry`() {
        val d1 = deferred()
        val d2 = deferred()
        registry.register("a", 1, d1)
        registry.register("b", 1, d2)

        val errorResponse = ResponseEntity.status(503).header("Retry-After", "1").build<Any>()
        registry.failAll(errorResponse)

        assertThat(d1.result).isEqualTo(errorResponse)
        assertThat(d2.result).isEqualTo(errorResponse)
        assertThat(registry.size()).isZero
    }
}
