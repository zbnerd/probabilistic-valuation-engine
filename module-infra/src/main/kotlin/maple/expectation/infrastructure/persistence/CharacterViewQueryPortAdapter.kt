package maple.expectation.infrastructure.persistence

import maple.expectation.core.domain.model.character.CharacterView
import maple.expectation.core.port.inbound.CharacterViewQueryPort
import maple.expectation.infrastructure.persistence.entity.CharacterValuationViewEntity
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.util.Optional

/**
 * CharacterViewQueryPort 구현체 (ADR-005, Issue #639)
 *
 * <p>책임: CharacterViewQueryServicePostgres에 위임하여 JPA 엔티티를 DTO로 변환
 *
 * <p>DIP 위반 해결:
 * <ul>
 *   <li>module-web → CharacterViewQueryPort (module-core)</li>
 *   <li>module-infra → CharacterViewQueryPortAdapter → JPA Entity</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(name = ["app.v5.enabled"], havingValue = "true", matchIfMissing = false)
class CharacterViewQueryPortAdapter(
    private val queryService: CharacterViewQueryServicePostgres,
) : CharacterViewQueryPort {
    private val log = LoggerFactory.getLogger(CharacterViewQueryPortAdapter::class.java)

    override fun findByUserIgn(userIgn: String): Optional<CharacterView> {
        val entity = queryService.findByUserIgn(userIgn)
        return if (entity != null) {
            Optional.of(CharacterViewEntityAdapter(entity))
        } else {
            Optional.empty()
        }
    }

    override fun deleteByUserIgn(userIgn: String) {
        queryService.deleteByUserIgn(userIgn)
    }

    /**
     * JPA Entity를 CharacterView 인터페이스로 어댑트
     */
    private class CharacterViewEntityAdapter(
        private val entity: CharacterValuationViewEntity,
    ) : CharacterView {
        override val userIgn: String
            get() = entity.userIgn
        override val calculatedAt: java.time.Instant?
            get() = entity.calculatedAt
        override val fromCache: Boolean?
            get() = entity.fromCache
        override val totalExpectedCost: Long?
            get() = entity.totalExpectedCost
        override val maxPresetNo: Int?
            get() = entity.maxPresetNo
        override val presets: List<CharacterView.PresetView>?
            get() = entity.presets?.map { presetEntity ->
                CharacterView.PresetView(
                    presetNo = presetEntity.presetNo,
                    totalExpectedCost = presetEntity.totalExpectedCost,
                    totalCostText = presetEntity.totalCostText,
                    costBreakdown = presetEntity.costBreakdown?.let { breakdown ->
                        CharacterView.CostBreakdownView(
                            blackCubeCost = breakdown.blackCubeCost,
                            redCubeCost = breakdown.redCubeCost,
                            additionalCubeCost = breakdown.additionalCubeCost,
                            starforceCost = breakdown.starforceCost,
                            flameCost = breakdown.flameCost,
                        )
                    },
                    items = presetEntity.items?.map { item ->
                        CharacterView.ItemExpectationView(
                            itemName = item.itemName,
                            expectedCost = item.expectedCost,
                            costText = item.costText,
                        )
                    },
                )
            }
    }
}
