package maple.expectation.adapter.outgoing

import java.util.UUID
import maple.expectation.infrastructure.persistence.repository.OutboxEventRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

@ExtendWith(MockitoExtension::class)
class OutboxEventPortAdapterTest {

    @Mock lateinit var repo: OutboxEventRepository

    @Mock lateinit var jdbc: NamedParameterJdbcTemplate

    @InjectMocks lateinit var adapter: OutboxEventPortAdapter

    @Test
    fun `insertIfAbsent delegates to ON CONFLICT DO NOTHING`() {
        val jobId = UUID.randomUUID()
        whenever(repo.insertIfAbsent(any(), eq("CALCULATION_COMPLETED"), eq(jobId), any())).thenReturn(1)

        val result = adapter.insertIfAbsent("CALCULATION_COMPLETED", jobId, "{}")

        assertThat(result).isTrue()
    }

    @Test
    fun `insertIfAbsent returns false when conflict`() {
        val jobId = UUID.randomUUID()
        whenever(repo.insertIfAbsent(any(), eq("CALCULATION_COMPLETED"), eq(jobId), any())).thenReturn(0)

        val result = adapter.insertIfAbsent("CALCULATION_COMPLETED", jobId, "{}")

        assertThat(result).isFalse()
    }

    @Test
    fun `findCompletedJobsMissingOutboxEvents returns ids from jdbc`() {
        val ids = listOf(UUID.randomUUID(), UUID.randomUUID())
        whenever(jdbc.queryForList(any<String>(), any<Map<String, Any>>(), eq(UUID::class.java))).thenReturn(ids)

        val result = adapter.findCompletedJobsMissingOutboxEvents(50)

        assertThat(result).hasSize(2).containsExactlyElementsOf(ids)
    }

    @Test
    fun `findCompletedJobsMissingOutboxEvents returns empty list when jdbc yields nothing`() {
        whenever(jdbc.queryForList(any<String>(), any<Map<String, Any>>(), eq(UUID::class.java))).thenReturn(emptyList())

        val result = adapter.findCompletedJobsMissingOutboxEvents(50)

        assertThat(result).isEmpty()
    }

    @Test
    fun `findCompletedJobsMissingOutboxEvents passes limit to jdbc query`() {
        whenever(jdbc.queryForList(any<String>(), any<Map<String, Any>>(), eq(UUID::class.java))).thenReturn(emptyList())

        adapter.findCompletedJobsMissingOutboxEvents(25)

        verify(jdbc).queryForList(any<String>(), eq(mapOf("limit" to 25)), eq(UUID::class.java))
    }
}
