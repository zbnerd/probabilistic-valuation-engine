package maple.restcontroller.ranking

import maple.restcontroller.config.V6ReadProperties

class EquipmentRankingService(
    private val cacheService: EquipmentRankingCacheService,
    private val queryService: EquipmentRankingQueryService,
    private val properties: V6ReadProperties,
) {
    fun topByTotalCost(presetNo: Int): EquipmentRankingResponse {
        val limit = properties.ranking.topSize.coerceAtLeast(1)
        val cached = cacheService.topByTotalCost(presetNo, limit)
        if (!cached.isNullOrEmpty()) {
            return EquipmentRankingResponse(presetNo, EquipmentRankingSource.REDIS, cached)
        }

        return EquipmentRankingResponse(
            presetNo = presetNo,
            source = EquipmentRankingSource.DB,
            rankings = queryService.topByTotalCost(presetNo, limit),
        )
    }
}
