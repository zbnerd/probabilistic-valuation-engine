package maple.expectation.infrastructure.persistence.entity

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.persistence.*
import java.time.Instant
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

/**
 * V5 CQRS: PostgreSQL Read Model for Character Valuation Views
 *
 * MongoDB CharacterValuationView의 PostgreSQL 마이그레이션 (Issue #590)
 */
@Entity
@Table(
    name = "character_valuation_views",
    indexes = [
        Index(name = "idx_character_valuation_user_ign", columnList = "user_ign"),
        Index(name = "idx_character_valuation_message_id", columnList = "message_id", unique = true),
        Index(name = "idx_character_valuation_calculated_at", columnList = "calculated_at"),
    ],
)
class CharacterValuationViewEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @JsonIgnore // Internal JPA field — excluded from read model JSON
    var id: Long? = null,

    @Version
    @Column(name = "jpa_version")
    @JsonIgnore // Internal JPA field — excluded from read model JSON
    var jpaVersion: Long? = null,

    @Column(name = "user_ign", nullable = false, length = 100)
    var userIgn: String,

    @Column(name = "message_id", unique = true, length = 100)
    var messageId: String? = null,

    @Column(name = "character_ocid", length = 100)
    var characterOcid: String? = null,

    @Column(name = "character_class", length = 50)
    var characterClass: String? = null,

    @Column(name = "character_level")
    var characterLevel: Int? = null,

    @Column(name = "calculated_at")
    var calculatedAt: Instant? = null,

    @Column(name = "last_api_sync_at")
    var lastApiSyncAt: Instant? = null,

    /**
     * Event version for causal consistency
     */
    @Column(name = "version")
    @JsonIgnore // Internal field — excluded from read model JSON
    var version: Long? = null,

    /**
     * Last applied event version for ordering validation
     */
    @Column(name = "last_applied_version")
    @JsonIgnore // Internal field — excluded from read model JSON
    var lastAppliedVersion: Long? = null,

    @Column(name = "total_expected_cost")
    var totalExpectedCost: Long? = null,

    @Column(name = "max_preset_no")
    var maxPresetNo: Int? = null,

    /**
     * Preset data stored as JSONB
     */
    @Column(name = "presets", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    var presets: List<PresetView>? = null,

    @Column(name = "from_cache")
    var fromCache: Boolean? = null,
) {
    /**
     * Preset view data (embedded in JSONB)
     */
    data class PresetView(
        val presetNo: Int? = null,
        val totalExpectedCost: Long? = null,
        val totalCostText: String? = null,
        val costBreakdown: CostBreakdownView? = null,
        val items: List<ItemExpectationView>? = null,
    )

    data class CostBreakdownView(
        val blackCubeCost: Long? = null,
        val redCubeCost: Long? = null,
        val additionalCubeCost: Long? = null,
        val starforceCost: Long? = null,
        val flameCost: Long? = null,
    )

    data class ItemExpectationView(
        val itemName: String? = null,
        val expectedCost: Long? = null,
        val costText: String? = null,
    )
}
