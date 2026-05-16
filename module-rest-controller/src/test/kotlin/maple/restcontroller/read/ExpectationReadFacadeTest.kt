package maple.restcontroller.read

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import maple.restcontroller.metrics.V6ReadMetrics
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.ResponseEntity
import org.springframework.web.context.request.async.DeferredResult

class ExpectationReadFacadeTest {

    private val meterRegistry = SimpleMeterRegistry()
    private lateinit var buffer: LocalRequestBuffer
    private lateinit var registry: InflightRequestRegistry
    private lateinit var metrics: V6ReadMetrics
    private lateinit var facade: ExpectationReadFacade

    @BeforeEach
    fun setup() {
        buffer = LocalRequestBuffer(100)
        registry = InflightRequestRegistry()
        metrics = V6ReadMetrics(meterRegistry, buffer, registry)
        facade = ExpectationReadFacade(registry, buffer, metrics)
    }

    private fun enqueue(ign: String): DeferredResult<ResponseEntity<*>> {
        val deferred = DeferredResult<ResponseEntity<*>>()
        facade.enqueue(ign, deferred)
        return deferred
    }

    @Test
    fun `enqueue dedup miss offers to buffer`() {
        val deferred = enqueue("진격캐넌")

        assertThat(deferred).isNotNull
        assertThat(buffer.size()).isEqualTo(1)
        assertThat(meterRegistry.counter("v6_dedup_miss_total").count()).isEqualTo(1.0)
        assertThat(meterRegistry.counter("v6_request_total").count()).isEqualTo(1.0)
    }

    @Test
    fun `enqueue dedup hit does not add to buffer`() {
        enqueue("진격캐넌")
        enqueue("진격캐넌")

        assertThat(buffer.size()).isEqualTo(1)
        assertThat(meterRegistry.counter("v6_dedup_hit_total").count()).isEqualTo(1.0)
        assertThat(meterRegistry.counter("v6_request_total").count()).isEqualTo(2.0)
    }

    @Test
    fun `enqueue sets 503 error when buffer is full`() {
        val smallBuffer = LocalRequestBuffer(1)
        val smallMetrics = V6ReadMetrics(SimpleMeterRegistry(), smallBuffer, registry)
        val fullFacade = ExpectationReadFacade(registry, smallBuffer, smallMetrics)

        val d1 = DeferredResult<ResponseEntity<*>>()
        fullFacade.enqueue("a", d1)

        val d2 = DeferredResult<ResponseEntity<*>>()
        fullFacade.enqueue("b", d2)

        assertThat(smallMetrics.bufferRejectedTotal.count()).isEqualTo(1.0)
        assertThat(d2.result).isNotNull
    }

    @Test
    fun `different userIgns are both buffered`() {
        enqueue("a")
        enqueue("b")

        assertThat(buffer.size()).isEqualTo(2)
        assertThat(registry.size()).isEqualTo(2)
    }
}
