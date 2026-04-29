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
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class OutboxEventPortAdapterTest {

    @Mock lateinit var repo: OutboxEventRepository

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
}
