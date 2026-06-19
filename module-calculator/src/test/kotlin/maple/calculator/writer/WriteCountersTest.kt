package maple.calculator.writer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WriteCountersTest {

    @Test
    fun `initial values are zero`() {
        val c = WriteCounters()
        assertThat(c.records.get()).isZero()
        assertThat(c.uncompressedBytes.get()).isZero()
        assertThat(c.compressedBytes.get()).isZero()
    }

    @Test
    fun `concurrent increment from many threads yields correct sum`() {
        val c = WriteCounters()
        val pool = Executors.newFixedThreadPool(8)
        val perThread = 1000
        val threadCount = 8
        val futures = (1..threadCount).map {
            pool.submit {
                repeat(perThread) { c.records.incrementAndGet() }
            }
        }
        futures.forEach { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()
        assertThat(c.records.get()).isEqualTo((perThread * threadCount).toLong())
    }
}
