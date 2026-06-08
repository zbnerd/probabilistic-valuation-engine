package maple.restcontroller.read

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LocalRequestBufferTest {

    private fun createBuffer(capacity: Int = 10): LocalRequestBuffer = LocalRequestBuffer(capacity)

    private fun request(ign: String) = ReadRequest(userIgn = ign)

    @Test
    fun `should offer and drain requests`() {
        val buffer = createBuffer()
        assertThat(buffer.offer(request("a"))).isTrue
        assertThat(buffer.offer(request("b"))).isTrue
        assertThat(buffer.size()).isEqualTo(2)

        val drained = buffer.drain(10)
        assertThat(drained).hasSize(2)
        assertThat(drained.map { it.userIgn }).containsExactly("a", "b")
        assertThat(buffer.size()).isZero
    }

    @Test
    fun `should drain up to maxItems`() {
        val buffer = createBuffer()
        repeat(5) { buffer.offer(request("ign$it")) }

        val drained = buffer.drain(3)
        assertThat(drained).hasSize(3)
        assertThat(buffer.size()).isEqualTo(2)
    }

    @Test
    fun `should reject offer when at capacity`() {
        val buffer = createBuffer(capacity = 2)
        assertThat(buffer.offer(request("a"))).isTrue
        assertThat(buffer.offer(request("b"))).isTrue
        assertThat(buffer.offer(request("c"))).isFalse
        assertThat(buffer.size()).isEqualTo(2)
    }

    @Test
    fun `should reject offer after stopAccepting`() {
        val buffer = createBuffer()
        buffer.stopAccepting()
        assertThat(buffer.offer(request("a"))).isFalse
        assertThat(buffer.size()).isZero
    }

    @Test
    fun `should reject offer when stopped even if capacity available`() {
        val buffer = createBuffer(capacity = 10)
        buffer.offer(request("a"))
        buffer.stopAccepting()
        assertThat(buffer.offer(request("b"))).isFalse
    }

    @Test
    fun `failAllPending should clear queue`() {
        val buffer = createBuffer()
        repeat(5) { buffer.offer(request("ign$it")) }
        assertThat(buffer.size()).isEqualTo(5)

        buffer.failAllPending()
        assertThat(buffer.size()).isZero
        assertThat(buffer.isEmpty()).isTrue
    }

    @Test
    fun `drain from empty buffer returns empty list`() {
        val buffer = createBuffer()
        assertThat(buffer.drain(10)).isEmpty()
    }

    @Test
    fun `isEmpty returns true when no elements`() {
        val buffer = createBuffer()
        assertThat(buffer.isEmpty()).isTrue
        buffer.offer(request("a"))
        assertThat(buffer.isEmpty()).isFalse
    }
}
