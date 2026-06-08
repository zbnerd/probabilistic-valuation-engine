package maple.restcontroller.read

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LocalRequestBuffer(
    private val maxCapacity: Int,
) : RequestBuffer {

    private val queue = ConcurrentLinkedQueue<ReadRequest>()
    private val counter = AtomicInteger(0)
    private val accepting = AtomicBoolean(true)

    override fun offer(request: ReadRequest): Boolean {
        if (!accepting.get()) return false
        if (counter.incrementAndGet() > maxCapacity) {
            counter.decrementAndGet()
            return false
        }
        if (!queue.offer(request)) {
            counter.decrementAndGet()
            return false
        }
        return true
    }

    override fun drain(maxItems: Int): List<ReadRequest> {
        val result = mutableListOf<ReadRequest>()
        repeat(maxItems) {
            val element = queue.poll() ?: return@repeat
            counter.decrementAndGet()
            result.add(element)
        }
        return result
    }

    override fun size(): Int = counter.get()

    override fun isEmpty(): Boolean = queue.isEmpty()

    override fun stopAccepting() {
        accepting.set(false)
    }

    override fun failAllPending() {
        queue.clear()
        counter.set(0)
    }
}
