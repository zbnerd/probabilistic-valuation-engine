package maple.restcontroller.ranking

import maple.restcontroller.config.V6ReadProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class EquipmentRankingServiceTest {

    private val cacheService: EquipmentRankingCacheService = mock()
    private val queryService: EquipmentRankingQueryService = mock()
    private val properties = V6ReadProperties().apply {
        ranking.topSize = 10
    }
    private val service = EquipmentRankingService(cacheService, queryService, properties)

    @Test
    fun `uses Redis ranking when Redis read succeeds`() {
        val cached = listOf(entry("캐릭터A", 1, "1000"))
        whenever(cacheService.topByTotalCost(1, 10)).thenReturn(cached)

        val result = service.topByTotalCost(1)

        assertThat(result.source).isEqualTo(EquipmentRankingSource.REDIS)
        assertThat(result.rankings).isEqualTo(cached)
    }

    @Test
    fun `falls back to DB when Redis read fails`() {
        val db = listOf(entry("캐릭터B", 1, "2000"))
        whenever(cacheService.topByTotalCost(1, 10)).thenReturn(null)
        whenever(queryService.topByTotalCost(1, 10)).thenReturn(db)

        val result = service.topByTotalCost(1)

        assertThat(result.source).isEqualTo(EquipmentRankingSource.DB)
        assertThat(result.rankings).isEqualTo(db)
    }

    private fun entry(userIgn: String, presetNo: Int, totalCost: String): EquipmentRankingEntry =
        EquipmentRankingEntry(
            rank = 1,
            userIgn = userIgn,
            presetNo = presetNo,
            totalCost = totalCost.toLong(),
        )
}
