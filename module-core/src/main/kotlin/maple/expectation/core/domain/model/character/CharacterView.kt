package maple.expectation.core.domain.model.character

import java.time.Instant
import java.util.Optional

/**
 * Character Valuation View DTO (Issue #639 DIP Fix)
 *
 * <p>Abstracts the JPA entity from the web layer.
 * Provides read-only access to character valuation data.
 *
 * <p>Used by V5 CQRS query side for fast PostgreSQL reads.
 */
interface CharacterView {
    /** 캐릭터 IGN */
    val userIgn: String

    /** V5 queue message ID */
    val messageId: String?

    /** 계산 시간 */
    val calculatedAt: Instant?

    /** 캐시 여부 */
    val fromCache: Boolean?

    /** 총 기대값 비용 (Long) */
    val totalExpectedCost: Long?

    /** 최대 프리셋 번호 */
    val maxPresetNo: Int?

    /** 프리셋 데이터 (JSONB) */
    val presets: List<PresetView>?

    /**
     * Preset view data
     */
    data class PresetView(
        val presetNo: Int?,
        val totalExpectedCost: Long?,
        val totalCostText: String?,
        val costBreakdown: CostBreakdownView?,
        val items: List<ItemExpectationView>?,
    )

    /**
     * Cost breakdown view
     */
    data class CostBreakdownView(
        val blackCubeCost: Long?,
        val redCubeCost: Long?,
        val additionalCubeCost: Long?,
        val starforceCost: Long?,
        val flameCost: Long?,
    )

    /**
     * Item expectation view
     */
    data class ItemExpectationView(
        val itemName: String?,
        val expectedCost: Long?,
        val costText: String?,
    )

    companion object {
        /**
         * Create empty view for Optional handling
         */
        @JvmStatic
        fun empty(): Optional<CharacterView> = Optional.empty()
    }
}
