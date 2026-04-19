package maple.expectation.infrastructure.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * V5 Query Server: PostgreSQL Read Model for Character Expectation
 *
 * Stores GZIP-compressed JSON payload as BYTEA for efficient storage.
 * Uses user_ign as primary key for singleton read model per character.
 * Updated atomically via upsert_expectation_read_model() function.
 */
@Entity
@Table(name = "character_expectation_read_model")
class ExpectationReadModelEntity(
    @Id
    @Column(name = "user_ign", length = 100)
    var userIgn: String,

    @Column(name = "payload", nullable = false)
    var payload: ByteArray,

    @Column(name = "calculated_at", nullable = false)
    var calculatedAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    override fun equals(other: Any?): Boolean = other is ExpectationReadModelEntity && userIgn == other.userIgn

    override fun hashCode(): Int = userIgn.hashCode()

    override fun toString(): String = "ExpectationReadModelEntity(userIgn=$userIgn, calculatedAt=$calculatedAt)"
}
