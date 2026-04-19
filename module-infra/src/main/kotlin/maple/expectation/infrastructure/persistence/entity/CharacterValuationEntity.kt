package maple.expectation.infrastructure.persistence.entity

import jakarta.persistence.*
import java.time.Instant

/**
 * V5 CQRS: PostgreSQL JSONB Read Model for Character Valuation Views
 *
 * <h3>Purpose</h3>
 *
 * <ul>
 *   <li>Read-optimized entity for fast expectation queries
 *   <li>Denormalized: All preset data embedded in single document (JSONB)
 *   <li>Indexed: userIgn for O(1) lookup performance
 * </ul>
 *
 * <h3>CQRS Separation</h3>
 *
 * <ul>
 *   <li><b>Query Side:</b> This entity (PostgreSQL JSONB)
 *   <li><b>Command Side:</b> MySQL game_character, character_equipment
 *   <li><b>Sync:</b> PGMQ event queue (direct publish)
 * </ul>
 *
 * <h3>JSONB Strategy</h3>
 *
 * <p>Complex nested structures (presets, costBreakdown) stored as JSONB for:
 * <ul>
 *   <li>Flexible schema evolution
 *   <li>Efficient GIN-indexed queries
 *   <li>Binary JSON storage (faster than JSON)
 * </ul>
 *
 * @see <a href="../../../../../../docs/01_ADR/ADR-036-v5-cqrs-mongodb.md">ADR-036: V5 CQRS MongoDB → PostgreSQL JSONB Migration</a>
 */
@Entity
@Table(
    name = "character_valuation_views",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_valuation_message_id", columnNames = ["message_id"]),
    ],
    indexes = [
        Index(name = "idx_valuation_user_ign", columnList = "user_ign"),
        Index(name = "idx_valuation_calculated", columnList = "calculated_at DESC"),
    ],
)
open class CharacterValuationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Column(name = "user_ign", nullable = false, length = 50)
    open var userIgn: String? = null

    @Column(name = "message_id", unique = true, length = 255)
    open var messageId: String? = null

    @Column(name = "character_ocid", length = 64)
    open var characterOcid: String? = null

    @Column(name = "character_class", length = 50)
    open var characterClass: String? = null

    @Column(name = "character_level")
    open var characterLevel: Int? = null

    @Column(name = "calculated_at", nullable = false)
    open var calculatedAt: Instant? = null

    @Column(name = "last_api_sync_at")
    open var lastApiSyncAt: Instant? = null

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
    @Column(name = "version", nullable = false)
    open var version: Long? = null

    /**
     * Last applied event version for ordering validation
     *
     * <p>Used by PostgreSQLSyncWorker to buffer out-of-order events.
     */
    @Column(name = "last_applied_version")
    open var lastAppliedVersion: Long? = null

    @Column(name = "total_expected_cost")
    open var totalExpectedCost: Long? = null

    @Column(name = "max_preset_no")
    open var maxPresetNo: Int? = null

    /**
     * Presets stored as JSONB for flexible schema and efficient querying
     *
     * <p>Contains JSON string representation of presets list.
     * Application layer is responsible for serialization/deserialization.
     */
    @Column(name = "presets", columnDefinition = "jsonb")
    open var presets: String? = null

    @Column(name = "from_cache")
    open var fromCache: Boolean? = null

    /**
     * Optimistic locking version for concurrent update prevention
     *
     * <p>Automatically incremented by JPA on each update.
     * Prevents lost updates in batch-realtime race conditions.
     */
    @Version
    @Column(name = "jpa_version", nullable = false)
    open var jpaVersion: Long = 0

    protected constructor()

    /**
     * Constructor for creating a new entity with minimal required fields.
     *
     * @param userIgn User in-game name (required)
     * @param calculatedAt Calculation timestamp (defaults to now)
     * @param version Event version (defaults to 0)
     */
    constructor(
        userIgn: String?,
        calculatedAt: Instant? = Instant.now(),
        version: Long? = 0L,
    ) {
        this.userIgn = userIgn
        this.calculatedAt = calculatedAt
        this.version = version
    }

    // Note: MongoDB migration code removed in Issue #590
    // Use CharacterValuationViewEntity for new implementations
}
