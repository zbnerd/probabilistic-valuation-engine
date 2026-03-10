package maple.expectation.infrastructure.mongodb

import com.fasterxml.jackson.annotation.JsonIgnore
import java.time.Instant
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document

/**
 * V5 CQRS: MongoDB Read Model for Character Valuation Views
 *
 * <h3>Purpose</h3>
 *
 * <ul>
 *   <li>Read-optimized document for fast expectation queries
 *   <li>Denormalized: All preset data embedded in single document
 *   <li>Indexed: userIgn for O(1) lookup performance
 * </ul>
 *
 * <h3>CQRS Separation</h3>
 *
 * <ul>
 *   <li><b>Query Side:</b> This document (MongoDB)
 *   <li><b>Command Side:</b> MySQL game_character, character_equipment
 *   <li><b>Sync:</b> Redis Stream character-sync topic
 * </ul>
 *
 * <h3>TTL Strategy</h3>
 *
 * <p>24-hour automatic expiry. Stale data removed without manual invalidation. TTL index is created
 * on {@code calculatedAt} field via {@link MongoDBConfig}.
 */
@Document(collection = "character_valuation_views")
@CompoundIndex(def = "{'userIgn': 1, 'calculatedAt': -1}")
data class CharacterValuationView(
    @Id var id: String? = null,

    @Indexed var userIgn: String? = null,

    @Indexed(unique = true)
    var messageId: String? = null,

    @Indexed var characterOcid: String? = null,

    var characterClass: String? = null,

    var characterLevel: Int? = null,

    var calculatedAt: Instant? = null,

    var lastApiSyncAt: Instant? = null,

    /**
     * Event version for causal consistency (Unit 4: Event Ordering & Versioning)
     *
     * <p>Ensures events are applied in monotonic order to prevent out-of-order corruption.
     *
     * <ul>
     *   <li>Events with version <= lastAppliedVersion are skipped (already applied)
     *   <li>Events with version > lastAppliedVersion + 1 are buffered (out-of-order)
     *   <li>Events with version == lastAppliedVersion + 1 are applied immediately
     * </ul>
     */
    @Indexed var version: Long? = null,

    /**
     * Last applied event version for ordering validation
     *
     * <p>Used by MongoDBSyncWorker to buffer out-of-order events.
     */
    var lastAppliedVersion: Long? = null,

    @Indexed var totalExpectedCost: Long? = null,

    @JsonIgnore var maxPresetNo: Int? = null,

    var presets: List<PresetView>? = null,

    var fromCache: Boolean? = null,
) {
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
