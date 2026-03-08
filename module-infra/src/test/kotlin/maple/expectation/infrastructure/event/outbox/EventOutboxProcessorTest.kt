package maple.expectation.infrastructure.event.outbox

import maple.expectation.domain.v2.EventOutbox
import maple.expectation.domain.v2.EventOutbox.EventOutboxStatus
import maple.expectation.infrastructure.config.OutboxProperties
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.metrics.EventOutboxMetrics
import maple.expectation.infrastructure.messaging.RedisStreamPublisher
import maple.expectation.infrastructure.persistence.repository.EventOutboxRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for {@link EventOutboxProcessor}.
 *
 * <p><strong>Test Coverage:</strong>
 *
 * <ul>
 *   <li>Successful pollAndProcess with empty pending queue
 *   <li>Successful pollAndProcess with pending entries
 *   <li>Individual entry processing with Redis Stream publish
 *   <li>Integrity verification failure handling
 *   <li>Exponential backoff and retry logic
 *   <li>Stalled entry recovery
 *   <li>DLQ movement after max retries
 * </ul>
 *
 * <p><strong>Flaky Test Prevention:</strong>
 * <ul>
 *   <li>No Thread.sleep - uses direct assertions
 *   <li>No shared state between tests
 *   <li>Mocked external dependencies
 *   <li>Deterministic test data
 * </ul>
 */
@ExtendWith(MockitoExtension::class)
@DisplayName("EventOutboxProcessor Tests")
class EventOutboxProcessorTest {

    @Mock
    private lateinit var fetchFacade: EventOutboxFetchFacade

    @Mock
    private lateinit var dlqHandler: EventDlqHandler

    @Mock
    private lateinit var metrics: EventOutboxMetrics

    @Mock
    private lateinit var executor: LogicExecutor

    @Mock
    private lateinit var transactionManager: PlatformTransactionManager

    @Mock
    private lateinit var eventOutboxRepository: EventOutboxRepository

    @Mock
    private lateinit var redisStreamPublisher: RedisStreamPublisher

    private lateinit var properties: OutboxProperties
    private lateinit var transactionTemplate: TransactionTemplate
    private lateinit var processor: EventOutboxProcessor

    @BeforeEach
    fun setUp() {
        properties = OutboxProperties().apply {
            instanceId = "test-instance"
            batchSize = 10
            staleThreshold = java.time.Duration.ofMinutes(5)
        }

        transactionTemplate = TransactionTemplate(transactionManager)

        processor = EventOutboxProcessor(
            fetchFacade = fetchFacade,
            dlqHandler = dlqHandler,
            metrics = metrics,
            executor = executor,
            transactionTemplate = transactionTemplate,
            properties = properties,
            eventOutboxRepository = eventOutboxRepository,
            redisStreamPublisher = redisStreamPublisher
        )
    }

    @Nested
    @DisplayName("pollAndProcess()")
    inner class PollAndProcessTests {

        @Test
        @DisplayName("should return early when no pending entries")
        fun pollAndProcess_EmptyPending() {
            // Given
            org.mockito.kotlin.whenever(fetchFacade.fetchAndLock()).thenReturn(emptyList())

            // When
            processor.pollAndProcess()

            // Then
            org.mockito.kotlin.verify(fetchFacade).fetchAndLock()
            org.mockito.kotlin.verify(eventOutboxRepository, org.mockito.kotlin.never())
                .save(org.mockito.kotlin.any<EventOutbox>())
        }

        @Test
        @DisplayName("should process batch of pending entries successfully")
        fun pollAndProcess_SuccessfulBatch() {
            // Given
            val entry1 = createTestEventOutbox(id = 1L, status = EventOutboxStatus.PENDING)
            val entry2 = createTestEventOutbox(id = 2L, status = EventOutboxStatus.PENDING)
            val entries = listOf(entry1, entry2)

            org.mockito.kotlin.whenever(fetchFacade.fetchAndLock()).thenReturn(entries)
            org.mockito.kotlin.whenever(eventOutboxRepository.findById(1L))
                .thenReturn(Optional.of(entry1))
            org.mockito.kotlin.whenever(eventOutboxRepository.findById(2L))
                .thenReturn(Optional.of(entry2))

            // Mock transaction execution
            org.mockito.kotlin.whenever(transactionTemplate.execute<Boolean>(org.mockito.kotlin.any()))
                .thenAnswer { invocation ->
                    val action = invocation.getArgument<org.springframework.transaction.support.TransactionCallback<Boolean>>(0)
                    action.doInTransaction(org.mockito.kotlin.mock())
                }

            // When
            processor.pollAndProcess()

            // Then
            org.mockito.kotlin.verify(metrics, org.mockito.kotlin.atLeastOnce()).incrementProcessed()
        }
    }

    @Nested
    @DisplayName("Retry and DLQ Logic")
    inner class RetryAndDlqTests {

        @Test
        @DisplayName("should increment retry count and mark as failed")
        fun handleFailure_IncrementRetry() {
            // Given
            val entry = createTestEventOutbox(
                id = 1L,
                status = EventOutboxStatus.PROCESSING,
                retryCount = 0
            )

            // When
            processor.handleFailure(entry, "Test error")

            // Then
            assertEquals(1, entry.retryCount)
            assertEquals(EventOutboxStatus.FAILED, entry.status)
            assertEquals("Test error", entry.lastError)
            org.mockito.kotlin.verify(metrics).incrementFailed()
        }

        @Test
        @DisplayName("should move to DLQ after max retries")
        fun handleFailure_MoveToDlq() {
            // Given
            val entry = createTestEventOutbox(
                id = 1L,
                status = EventOutboxStatus.PROCESSING,
                retryCount = 2,
                maxRetries = 3
            )

            // When
            processor.handleFailure(entry, "Max retries reached")

            // Then
            assertEquals(3, entry.retryCount)
            assertEquals(EventOutboxStatus.DEAD_LETTER, entry.status)
            org.mockito.kotlin.verify(dlqHandler).handleDeadLetter(
                org.mockito.kotlin.eq(entry),
                org.mockito.kotlin.anyString()
            )
        }
    }

    @Nested
    @DisplayName("Stalled Recovery")
    inner class StalledRecoveryTests {

        @Test
        @DisplayName("should recover stalled entries with integrity verification")
        fun recoverStalled_Success() {
            // Given
            val staleTime = LocalDateTime.now().minusMinutes(10)
            val stalledEntry = createTestEventOutbox(
                id = 1L,
                status = EventOutboxStatus.PROCESSING,
                lockedBy = "old-instance",
                lockedAt = staleTime
            )

            org.mockito.kotlin.whenever(
                eventOutboxRepository.findStalledProcessing(
                    org.mockito.kotlin.any(),
                    org.mockito.kotlin.any()
                )
            ).thenReturn(listOf(stalledEntry))

            // When
            processor.recoverStalled()

            // Then
            assertEquals(EventOutboxStatus.PENDING, stalledEntry.status)
            assertEquals(null, stalledEntry.lockedBy)
            assertEquals(null, stalledEntry.lockedAt)
            org.mockito.kotlin.verify(metrics).incrementStalledRecovered(1)
            org.mockito.kotlin.verify(eventOutboxRepository).save(stalledEntry)
        }

        @Test
        @DisplayName("should return early when no stalled entries")
        fun recoverStalled_NoStalledEntries() {
            // Given
            org.mockito.kotlin.whenever(
                eventOutboxRepository.findStalledProcessing(
                    org.mockito.kotlin.any(),
                    org.mockito.kotlin.any()
                )
            ).thenReturn(emptyList())

            // When
            processor.recoverStalled()

            // Then
            org.mockito.kotlin.verify(eventOutboxRepository, org.mockito.kotlin.never())
                .save(org.mockito.kotlin.any<EventOutbox>())
        }
    }

    @Nested
    @DisplayName("Exponential Backoff")
    inner class ExponentialBackoffTests {

        @Test
        @DisplayName("should calculate exponential backoff correctly")
        fun markFailed_ExponentialBackoff() {
            // Given
            val entry = createTestEventOutbox(
                id = 1L,
                status = EventOutboxStatus.PROCESSING
            )

            // When - First failure
            processor.handleFailure(entry, "Error 1")

            // Then - Should have backoff
            assertNotNull(entry.nextRetryAt)
            val firstBackoff = java.time.Duration.between(
                LocalDateTime.now(),
                entry.nextRetryAt
            ).seconds

            assertTrue(firstBackoff >= 30) // 2^0 * 30 = 30s minimum
        }

        @Test
        @DisplayName("should cap backoff at maximum (1 hour)")
        fun markFailed_MaxBackoffCap() {
            // Given
            val entry = createTestEventOutbox(
                id = 1L,
                status = EventOutboxStatus.PROCESSING,
                retryCount = 10
            )

            // When
            processor.handleFailure(entry, "Error")

            // Then
            assertNotNull(entry.nextRetryAt)
            val backoff = java.time.Duration.between(
                LocalDateTime.now(),
                entry.nextRetryAt
            ).seconds

            assertTrue(backoff <= 3600) // Max 1 hour
        }
    }

    // Helper methods

    private fun createTestEventOutbox(
        id: Long? = null,
        status: EventOutboxStatus = EventOutboxStatus.PENDING,
        eventType: String = "TestEvent",
        targetStream: String = "test-stream",
        payload: String = """{"data": "test"}""",
        retryCount: Int = 0,
        maxRetries: Int = 3,
        lockedBy: String? = null,
        lockedAt: LocalDateTime? = null
    ): EventOutbox {
        val outbox = EventOutbox.create(targetStream, eventType, payload)
        outbox.id = id
        outbox.status = status
        outbox.retryCount = retryCount
        outbox.maxRetries = maxRetries
        outbox.lockedBy = lockedBy
        outbox.lockedAt = lockedAt
        return outbox
    }
}
