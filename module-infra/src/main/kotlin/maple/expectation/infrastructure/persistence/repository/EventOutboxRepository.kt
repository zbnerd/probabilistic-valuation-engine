package maple.expectation.infrastructure.persistence.repository

import jakarta.persistence.LockModeType
import jakarta.persistence.QueryHint
import maple.expectation.domain.v2.EventOutbox
import maple.expectation.domain.v2.EventOutbox.EventOutboxStatus
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.QueryHints
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

/**
 * Event Outbox Repository for Multi-Stream Support (Issue #490)
 *
 * <h3>Lock Strategy (Issue #28)</h3>
 *
 * <p><b>SKIP LOCKED</b>: Database-level lock to prevent duplicate processing in distributed environments.
 *
 * <h4>Selection Rationale</h4>
 *
 * <ul>
 *   <li>General Pessimistic Lock causes waiting → decreased throughput
 *   <li>SKIP LOCKED skips locked rows → enables parallel processing
 *   <li>Advantage over Redis distributed lock: Independent operation even during Redis failures
 *   <li>Since this is an event outbox, @Version optimistic lock is combined for strong consistency
 * </ul>
 *
 * <h4>Multi-Stream Support</h4>
 *
 * <ul>
 *   <li>Queries support filtering by targetStream for routing to specific Redis streams
 *   <li>Custom queries for polling pending events by stream
 * </ul>
 *
 * @see <a href="docs/02_Technical_Guides/lock-strategy.md">Lock Strategy Guide - Event Outbox</a>
 * @see maple.expectation.domain.v2.EventOutbox
 */
interface EventOutboxRepository : JpaRepository<EventOutbox, Long> {

    /**
     * Find by target stream and status (for multi-stream routing)
     *
     * @param targetStream Redis stream name (e.g., "character-sync")
     * @param status Event status
     * @param pageable Pagination params
     * @return List of EventOutbox items
     */
    fun findByTargetStreamAndStatusOrderByCreatedAtAsc(
        @Param("targetStream") targetStream: String,
        @Param("status") status: EventOutboxStatus,
        pageable: Pageable
    ): List<EventOutbox>

    /**
     * Find by status ordered by creation time (general polling)
     *
     * @param status Event status
     * @param pageable Pagination params
     * @return List of EventOutbox items
     */
    fun findByStatusOrderByCreatedAtAsc(
        @Param("status") status: EventOutboxStatus,
        pageable: Pageable
    ): List<EventOutbox>

    /**
     * SKIP LOCKED Query (distributed batch processing)
     *
     * <p><b>Lock Strategy</b>: PESSIMISTIC_WRITE + SKIP LOCKED
     *
     * <p><b>QueryHint -2</b>: Specifies SKIP LOCKED in Hibernate 6.x
     *
     * <p><b>Selection Rationale</b>: In distributed environments where multiple instances poll Outbox simultaneously,
     * skip locked rows to enable parallel processing. No waiting, immediately process next record.
     *
     * @param statuses List of statuses to query
     * @param now Current time for nextRetryAt comparison
     * @param pageable Pagination params
     * @return List of locked EventOutbox items
     * @see <a href="docs/02_Technical_Guides/lock-strategy.md">Lock Strategy Guide - SKIP LOCKED</a>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query(
        "SELECT e FROM EventOutbox e WHERE e.status IN :statuses " +
            "AND e.nextRetryAt <= :now ORDER BY e.id"
    )
    fun findPendingWithLock(
        @Param("statuses") statuses: List<EventOutboxStatus>,
        @Param("now") now: LocalDateTime,
        pageable: Pageable
    ): List<EventOutbox>

    /**
     * SKIP LOCKED Query for specific target stream (multi-stream routing)
     *
     * <p>Same as findPendingWithLock but filters by targetStream for dedicated stream processing
     *
     * @param targetStream Redis stream name
     * @param statuses List of statuses to query
     * @param now Current time for nextRetryAt comparison
     * @param pageable Pagination params
     * @return List of locked EventOutbox items for specific stream
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query(
        "SELECT e FROM EventOutbox e WHERE e.targetStream = :targetStream " +
            "AND e.status IN :statuses " +
            "AND e.nextRetryAt <= :now ORDER BY e.id"
    )
    fun findPendingByTargetStreamWithLock(
        @Param("targetStream") targetStream: String,
        @Param("statuses") statuses: List<EventOutboxStatus>,
        @Param("now") now: LocalDateTime,
        pageable: Pageable
    ): List<EventOutbox>

    /**
     * Find stalled events for recovery (JVM crash response)
     *
     * <p>Find PROCESSING status events where lockedAt is before staleTime
     *
     * @param staleTime Stale judgment threshold time
     * @return List of stalled EventOutbox items
     */
    fun findByStatusAndLockedAtBefore(
        status: EventOutboxStatus,
        lockedAt: LocalDateTime
    ): List<EventOutbox>

    /**
     * Reset Stalled PROCESSING status (JVM crash recovery)
     *
     * <p>Restore PROCESSING status with lockedAt older than staleTime to PENDING
     */
    @Modifying
    @Query(
        "UPDATE EventOutbox e SET e.status = 'PENDING', e.lockedBy = NULL, e.lockedAt = NULL " +
            "WHERE e.status = 'PROCESSING' AND e.lockedAt < :staleTime"
    )
    fun resetStalledProcessing(@Param("staleTime") staleTime: LocalDateTime): Int

    /**
     * Find Stalled PROCESSING items (#229)
     *
     * <p>Individual item retrieval needed for integrity verification. Limit 100 for batch size.
     *
     * <h4>P1-4 Fix: SKIP LOCKED Added</h4>
     *
     * <p>Scale-out: multiple instances run recovery simultaneously → SKIP LOCKED prevents duplication.
     * Previously queried without @Lock, causing potential OptimisticLockException.
     *
     * @param staleTime Stale judgment threshold time
     * @return List of stalled EventOutbox items (max 100)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query(
        "SELECT e FROM EventOutbox e WHERE e.status = 'PROCESSING' AND e.lockedAt < :staleTime ORDER BY e.id"
    )
    fun findStalledProcessing(
        @Param("staleTime") staleTime: LocalDateTime,
        pageable: Pageable
    ): List<EventOutbox>

    /**
     * Find stale events by updated timestamp
     *
     * <p>Find events that haven't been updated within threshold time (for general stale detection)
     *
     * @param status Event status
     * @param threshold Time threshold for staleness
     * @return List of stale EventOutbox items
     */
    fun findByStatusAndUpdatedAtBefore(
        status: EventOutboxStatus,
        threshold: LocalDateTime
    ): List<EventOutbox>

    /**
     * Count by status (metrics)
     *
     * @param status Event status
     * @return Count of events with given status
     */
    fun countByStatus(status: EventOutboxStatus): Long

    /**
     * Count by status list (metrics for multiple statuses)
     *
     * @param statuses List of event statuses
     * @return Count of events with given statuses
     */
    fun countByStatusIn(statuses: List<EventOutboxStatus>): Long

    /**
     * Count by target stream and status (stream-specific metrics)
     *
     * @param targetStream Redis stream name
     * @param status Event status
     * @return Count of events for stream with given status
     */
    fun countByTargetStreamAndStatus(
        targetStream: String,
        status: EventOutboxStatus
    ): Long

    /**
     * Delete completed events older than threshold (cleanup)
     *
     * <p>Use for periodic cleanup of completed events
     *
     * @param status Event status (typically COMPLETED)
     * @param threshold Time threshold for deletion
     * @return Number of deleted events
     */
    @Modifying
    @Query(
        "DELETE FROM EventOutbox e WHERE e.status = :status AND e.updatedAt < :threshold"
    )
    fun deleteByStatusAndUpdatedAtBefore(
        @Param("status") status: EventOutboxStatus,
        @Param("threshold") threshold: LocalDateTime
    ): Int
}
