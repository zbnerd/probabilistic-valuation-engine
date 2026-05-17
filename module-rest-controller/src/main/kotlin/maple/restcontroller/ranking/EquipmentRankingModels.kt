package maple.restcontroller.ranking

data class EquipmentRankingEntry(
    val rank: Int,
    val userIgn: String,
    val presetNo: Int,
    val totalCost: Long,
)

data class EquipmentRankingResponse(
    val presetNo: Int,
    val source: EquipmentRankingSource,
    val rankings: List<EquipmentRankingEntry>,
)

enum class EquipmentRankingSource {
    REDIS,
    DB,
}
