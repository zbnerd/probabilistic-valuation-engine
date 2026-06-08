package maple.synchronizer.resolver

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

class OcidUserIgnResolverTest {

    private val jdbc: NamedParameterJdbcTemplate = mock()
    private val resolver = OcidUserIgnResolver(jdbc)

    @Test
    fun `should return empty map for empty ocids`() {
        val result = resolver.resolve(emptySet())
        assertThat(result).isEmpty()
    }

    @Test
    fun `should resolve ocids to userIgn map`() {
        @Suppress("UNCHECKED_CAST")
        whenever(jdbc.queryForList(any<String>(), any<MapSqlParameterSource>()))
            .thenReturn(
                listOf(
                    mapOf("ocid" to "ocid1", "user_ign" to "아델"),
                    mapOf("ocid" to "ocid2", "user_ign" to "강은호"),
                ),
            )

        val result = resolver.resolve(setOf("ocid1", "ocid2"))
        assertThat(result).hasSize(2)
        assertThat(result["ocid1"]).isEqualTo("아델")
        assertThat(result["ocid2"]).isEqualTo("강은호")
    }

    @Test
    fun `should handle partial miss — return only found mappings`() {
        @Suppress("UNCHECKED_CAST")
        whenever(jdbc.queryForList(any<String>(), any<MapSqlParameterSource>()))
            .thenReturn(
                listOf(
                    mapOf("ocid" to "ocid1", "user_ign" to "아델"),
                ),
            )

        val result = resolver.resolve(setOf("ocid1", "ocid_not_found"))
        assertThat(result).containsEntry("ocid1", "아델")
        assertThat(result).hasSize(1)
    }
}
