package maple.calculator.writer

import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * OutputStream wrapper that counts bytes written through it. Thread-safe via
 * [AtomicLong]. Used by [CalculationResultWriter] to track uncompressed bytes
 * before the artifact session applies gzip.
 */
class CountingOutputStream(
    private val delegate: OutputStream,
) : OutputStream() {

    private val counter = AtomicLong(0)

    /** Total bytes written through this stream. */
    val count: Long get() = counter.get()

    override fun write(b: Int) {
        delegate.write(b)
        counter.incrementAndGet()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
        counter.addAndGet(len.toLong())
    }

    override fun flush() = delegate.flush()

    override fun close() = delegate.close()
}
