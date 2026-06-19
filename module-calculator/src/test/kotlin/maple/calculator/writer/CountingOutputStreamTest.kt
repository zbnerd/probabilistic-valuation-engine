package maple.calculator.writer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CountingOutputStreamTest {

    @Test
    fun `write single byte increments counter by 1`() {
        val sink = ByteArrayOutputStream()
        val cos = CountingOutputStream(sink)
        cos.write(0x42)
        assertThat(cos.count).isEqualTo(1L)
        assertThat(sink.toByteArray()).containsExactly(0x42.toByte())
    }

    @Test
    fun `write array increments by length`() {
        val sink = ByteArrayOutputStream()
        val cos = CountingOutputStream(sink)
        cos.write(byteArrayOf(1, 2, 3, 4, 5))
        assertThat(cos.count).isEqualTo(5L)
    }

    @Test
    fun `write array with offset and length counts only specified range`() {
        val sink = ByteArrayOutputStream()
        val cos = CountingOutputStream(sink)
        cos.write(byteArrayOf(1, 2, 3, 4, 5), 1, 3)
        assertThat(cos.count).isEqualTo(3L)
        assertThat(sink.toByteArray()).containsExactly(2, 3, 4)
    }

    @Test
    fun `concurrent writes from multiple threads produce correct total`() {
        val sink = ByteArrayOutputStream()
        val cos = CountingOutputStream(sink)
        val pool = Executors.newFixedThreadPool(8)
        val futures = (1..100).map {
            pool.submit { cos.write(ByteArray(1024)) }
        }
        futures.forEach { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()
        assertThat(cos.count).isEqualTo(1024L * 100)
    }
}