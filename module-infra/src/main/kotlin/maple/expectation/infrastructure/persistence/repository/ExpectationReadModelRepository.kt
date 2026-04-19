package maple.expectation.infrastructure.persistence.repository

import maple.expectation.infrastructure.persistence.entity.ExpectationReadModelEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * V5 Query Server: Repository for Character Expectation Read Model
 *
 * Uses native PL/pgSQL function for atomic UPSERT with ON CONFLICT clause.
 * Ensures single-record-per-character constraint with safe concurrent updates.
 */
@Repository
interface ExpectationReadModelRepository : JpaRepository<ExpectationReadModelEntity, String> {

    /**
     * Atomic UPSERT using native PL/pgSQL function
     *
     * Calls upsert_expectation_read_model(userIgn, payload, calculatedAt)
     * which performs INSERT ... ON CONFLICT (user_ign) DO UPDATE
     *
     * @param userIgn Primary key (character name)
     * @param payload GZIP-compressed JSON as BYTEA
     * @param calculatedAt Timestamp of expectation calculation
     */
    @Query(value = "SELECT upsert_expectation_read_model(:userIgn, :payload, :calculatedAt)", nativeQuery = true)
    fun upsertNative(
        @Param("userIgn") userIgn: String,
        @Param("payload") payload: ByteArray,
        @Param("calculatedAt") calculatedAt: Instant,
    )
}
