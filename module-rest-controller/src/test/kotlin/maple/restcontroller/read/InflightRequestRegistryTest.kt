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
        val result = registry.register("진격캐넌", deferred())
        assertThat(result).isTrue
    }

    @Test
    fun `register returns false for duplicate userIgn (dedup hit)`() {
        registry.register("진격캐넌", deferred())
        val result = registry.register("진격캐넌", deferred())
        assertThat(result).isFalse
    }

    @Test
    fun `size returns unique userIgn count`() {
        registry.register("a", deferred())
        registry.register("b", deferred())
        registry.register("a", deferred())
        assertThat(registry.size()).isEqualTo(2)
    }

    @Test
    fun `getAndRemove returns all deferreds for userIgn`() {
        val d1 = deferred()
        val d2 = deferred()
        registry.register("진격캐넌", d1)
        registry.register("진격캐넌", d2)

        val removed = registry.getAndRemove("진격캐넌")
        assertThat(removed).containsExactly(d1, d2)
        assertThat(registry.size()).isZero
    }

    @Test
    fun `getAndRemove for non-existent key returns empty list`() {
        assertThat(registry.getAndRemove("none")).isEmpty()
    }

    @Test
    fun `cleanup removes specific deferred from list`() {
        val d1 = deferred()
        val d2 = deferred()
        registry.register("진격캐넌", d1)
        registry.register("진격캐넌", d2)

        registry.cleanup("진격캐넌", d1)

        val remaining = registry.getAndRemove("진격캐넌")
        assertThat(remaining).containsExactly(d2)
    }

    @Test
    fun `cleanup removes entry when list becomes empty`() {
        val d = deferred()
        registry.register("진격캐넌", d)
        registry.cleanup("진격캐넌", d)
        assertThat(registry.size()).isZero
    }

    @Test
    fun `failAll sets error on all pending deferreds and clears registry`() {
        val d1 = deferred()
        val d2 = deferred()
        registry.register("a", d1)
        registry.register("b", d2)

        val errorResponse = ResponseEntity.status(503).header("Retry-After", "1").build<Any>()
        registry.failAll(errorResponse)

        assertThat(d1.result).isEqualTo(errorResponse)
        assertThat(d2.result).isEqualTo(errorResponse)
        assertThat(registry.size()).isZero
    }
}
