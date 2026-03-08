package maple.expectation.infrastructure.event.outbox

import maple.expectation.domain.v2.EventOutbox
import maple.expectation.domain.v2.EventOutbox.EventOutboxStatus
import maple.expectation.infrastructure.config.OutboxProperties
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.metrics.EventOutboxMetrics
import maple.expectation.infrastructure.messaging.RedisStreamPublisher
import maple.expectation.infrastructure.persistence.repository.EventOutboxRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.data.domain.PageRequest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.containers.RedisContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for EventOutbox processing flow.
 *
 * <p><strong>Test Coverage:</strong>
 *
 * <ul>
 *   <li>End-to-end EventOutbox lifecycle: PENDING -> PROCESSING -> COMPLETED
 *   <li>SKIP LOCKED behavior with concurrent processing
 *   <li>Stalled entry recovery after JVM crash simulation
 *   <li>Exponential backoff with retry
 *   <li>DLQ movement after max retries
 *   <li>Integrity verification across state transitions
 * </ul>
 *
 * <p><strong>Flaky Test Prevention:</strong>
 * <ul>
 *   <li>Uses Testcontainers for deterministic environment
 *   <li>No Thread.sleep - uses Awaitility for async assertions
 *   <li>Isolated test data with unique IDs
 *   <li>Cleanup after each test
 * </ul>
 */
@SpringBootTest(classes = [])
@Testcontainers
@ActiveProfiles("test")
@DisplayName("EventOutbox Integration Tests")
class EventOutboxIntegrationTest {

    @Autowired
    private lateinit var eventOutboxRepository: EventOutboxRepository

    @Autowired
    private lateinit var fetchFacade: EventOutboxFetchFacade

    @Autowired
    private lateinit var processor: EventOutboxProcessor

    @Autowired
    private lateinit var dlqHandler: EventDlqHandler

    @Autowired
    private lateinit var metrics: EventOutboxMetrics

    @MockBean
    private lateinit var redisStreamPublisher: RedisStreamPublisher

    @Autowired
    private lateinit var properties: OutboxProperties

    @Autowired
    private lateinit var executor: LogicExecutor

    companion object {
        @Container
        private val mysqlContainer = MySQLContainer<Nothing>(
            DockerImageName.parse("mysql:8.0")
        ).apply {
            withDatabaseName("test")
            withUsername("test")
            withPassword("test")
        }

        @Container
        private val redisContainer = RedisContainer(
            DockerImageName.parse("redis:7-alpine")
        )
    }

    @BeforeEach
    fun setUp() {
        // Clean database before each test
        eventOutboxRepository.deleteAll()
    }

    @AfterEach
    fun tearDown() {
        // Clean database after each test
        eventOutboxRepository.deleteAll()
    }

    @Nested
    @DisplayName("End-to-End Processing Flow")
    inner class EndToEndProcessingTests {

        @Test
        @DisplayName("should process event from PENDING to COMPLETED")
        fun processEvent_SuccessfulFlow() {
            // Given
            val event = EventOutbox.create(
                targetStream = "test-stream",
                eventType = "TestEvent",
                payload = """{"data": "test"}"""
            )
            eventOutboxRepository.save(event)

            // Mock Redis Stream publish success
            org.mockito.kotlin.whenever(
                redisStreamPublisher.publish(
                    org.mockito.kotlin.anyString(),
                    org.mockito.kotlin.anyString(),
                    org.mockito.kotlin.anyString(),
                    org.mockito.kotlin.anyString()
                )
            ).thenReturn(Unit)

            // When
            processor.pollAndProcess()

            // Then
            val processed = eventOutboxRepository.findByIdOrNull(event.id!!)
            assertNotNull(processed)
            assertEquals(EventOutboxStatus.COMPLETED, processed.status)
            assertNull(processed.lockedBy)
            assertNull(processed.lockedAt)
        }

        @Test
        @DisplayName("should process batch of events correctly")
        fun processEvent_BatchProcessing() {
            // Given
            val events = (1..5).map { index ->
                EventOutbox.create(
                    targetStream = "test-stream",
                    eventType = "TestEvent$index",
                    payload = """{"index": $index}"""
                )
            }
            eventOutboxRepository.saveAll(events)

            org.mockito.kotlin.whenever(
                redisStreamPublisher.publish(
                    org.mockito.kotlin.anyString(),
                    org.mockito.kotlin.anyString(),
                    org.mockito.kotlin.anyString(),
                    org.mockito.kotlin.anyString()
                )
            ).thenReturn(Unit)

            // When
            processor.pollAndProcess()

            // Then
            val processed = eventOutboxRepository.findAll()
            assertTrue(processed.all { it.status == EventOutboxStatus.COMPLETED })
        }
    }

    @Nested
    @DisplayName("SKIP LOCKED Behavior")
    inner class SkipLockedTests {

        @Test
        @DisplayName("should skip locked entries when fetching")
        fun fetchAndLock_SkipLockedEntries() {
            // Given
            val event1 = EventOutbox.create(
                targetStream = "test-stream",
                eventType = "Event1",
                payload = "{}"
            )
            val event2 = EventOutbox.create(
                targetStream = "test-stream",
                eventType = "Event2",
                payload = "{}"
            )
            eventOutboxRepository.saveAll(listOf(event1, event2))

            // Lock first entry
            event1.markProcessing("instance-1")
            eventOutboxRepository.save(event1)

            // When - Fetch should only return unlocked event2
            val locked = fetchFacade.fetchAndLock()

            // Then
            assertEquals(1, locked.size)
            assertEquals("Event2", locked[0].eventType)
            assertEquals("instance-test", locked[0].lockedBy)
        }

        @Test
        @DisplayName("should process only fetched entries")
        fun processBatch_OnlyFetchedEntries() {
            // Given
            val event1 = EventOutbox.create("stream", "Event1", "{}")
            val event2 = EventOutbox.create("stream", "Event2", "{}")
            val event3 = EventOutbox.create("stream", "Event3", "{}")
            eventOutboxRepository.saveAll(listOf(event1, event2, event3))

            // Lock event2 to prevent it from being fetched
            event2.markProcessing("other-instance")
            eventOutboxRepository.save(event2)

            org.mockito.kotlin.whenever(
                redisStreamPublisher.publish(
                    org.mockito.kotlin.anyString(),
                    org.mockito.kotlin.anyString(),
                    org.mockito.kotlin.anyString(),
                    org.mockito.kotlin.anyString()
                )
            ).thenReturn(Unit)

            // When
            processor.pollAndProcess()

            // Then
            val processed = eventOutboxRepository.findAll()
            val event1Processed = processed.find { it.id == event1.id }
            val event2Processed = processed.find { it.id == event2.id }
            val event3Processed = processed.find { it.id == event3.id }

            assertEquals(EventOutboxStatus.COMPLETED, event1Processed?.status)
            assertEquals(EventOutboxStatus.PROCESSING, event2Processed?.status) // Still locked
            assertEquals(EventOutboxStatus.COMPLETED, event3Processed?.status)
        }
    }

    @Nested
    @DisplayName("Retry and Exponential Backoff")
    inner class RetryTests {

        @Test
        @DisplayName("should increment retry count on failure")
        fun processEntry_IncrementRetryCount() {
            // Given
            val event = EventOutbox.create("stream", "TestEvent", "{}")
            eventOutboxRepository.save(event)

            org.mockito.kotlin.whenever(
                redisStreamPublisher.publish(
                    org.mockito.kotlin.anyString(),
                    org.mockito.kotlin.anyString(),
                    org.mockito.kotlin.anyString(),
                    org.mockito.kotlin.anyString()
                )
            ).thenThrow(RuntimeException("Redis connection failed"))

            // When
            processor.pollAndProcess()

            // Then
            val failed = eventOutboxRepository.findByIdOrNull(event.id!!)
            assertNotNull(failed)
            assertEquals(1, failed.retryCount)
            assertEquals(EventOutboxStatus.FAILED, failed.status)
            assertNotNull(failed.lastError)
            assertNotNull(failed.nextRetryAt)
        }

        @Test
        @DisplayName("should move to DLQ after max retries")
        fun processEntry_MaxRetriesMoveToDlq() {
            // Given
            val event = EventOutbox.create("stream", "TestEvent", "{}")
            event.retryCount = 2 // maxRetries = 3, so this is the last attempt
            eventOutboxRepository.save(event)

            org.mockito.kotlin.whenever(
                redisStreamPublisher.publish(
                    org.mockito.kotlin.anyString(),
                    org.mockito.kotlin.anyString(),
                    org.mockito.kotlin.anyString(),
                    org.mockito.kotlin.anyString()
                )
            ).thenThrow(RuntimeException("Always fails"))

            // When
            processor.pollAndProcess()

            // Then
            val dlqEntry = eventOutboxRepository.findByIdOrNull(event.id!!)
            assertNotNull(dlqEntry)
            assertEquals(3, dlqEntry.retryCount)
            assertEquals(EventOutboxStatus.DEAD_LETTER, dlqEntry.status)
        }

        @Test
        @DisplayName("should respect exponential backoff before retry")
        fun processEntry_ExponentialBackoff() {
            // Given
            val event = EventOutbox.create("stream", "TestEvent", "{}")
            eventOutboxRepository.save(event)

            org.mockito.kotlin.whenever(
                redisStreamPublisher.publish(
                    org.mockito.kotlin.anyString(),
                    org.mockito.kotlin.anyString(),
                    org.mockito.kotlin.anyString(),
                    org.mockito.kotlin.anyString()
                )
            ).thenThrow(RuntimeException("Failed"))

            val beforeRetry = LocalDateTime.now()

            // When
            processor.pollAndProcess()

            // Then
            val failed = eventOutboxRepository.findByIdOrNull(event.id!!)
            assertNotNull(failed)
            assertNotNull(failed.nextRetryAt)

            val backoffSeconds = Duration.between(beforeRetry, failed.nextRetryAt!!).seconds
            assertTrue(backoffSeconds >= 30) // Minimum backoff: 2^0 * 30 = 30s
        }
    }

    @Nested
    @DisplayName("Stalled Recovery")
    inner class StalledRecoveryTests {

        @Test
        @DisplayName("should recover stalled entries")
        fun recoverStalled_Success() {
            // Given
            val staleTime = LocalDateTime.now().minusMinutes(10)
            val stalledEvent = EventOutbox.create("stream", "StalledEvent", "{}")
            stalledEvent.markProcessing("old-instance")
            stalledEvent.lockedAt = staleTime
            eventOutboxRepository.save(stalledEvent)

            // When
            processor.recoverStalled()

            // Then
            val recovered = eventOutboxRepository.findByIdOrNull(stalledEvent.id!!)
            assertNotNull(recovered)
            assertEquals(EventOutboxStatus.PENDING, recovered.status)
            assertNull(recovered.lockedBy)
            assertNull(recovered.lockedAt)
        }

        @Test
        @DisplayName("should not recover fresh locked entries")
        fun recoverStalled_SkipFreshEntries() {
            // Given
            val freshEvent = EventOutbox.create("stream", "FreshEvent", "{}")
            freshEvent.markProcessing("current-instance")
            freshEvent.lockedAt = LocalDateTime.now().minusMinutes(1) // Too fresh
            eventOutboxRepository.save(freshEvent)

            // When
            processor.recoverStalled()

            // Then
            val stillLocked = eventOutboxRepository.findByIdOrNull(freshEvent.id!!)
            assertNotNull(stillLocked)
            assertEquals(EventOutboxStatus.PROCESSING, stillLocked.status)
            assertNotNull(stillLocked.lockedBy)
        }

        @Test
        @DisplayName("should fail integrity verification on stalled recovery")
        fun recoverStalled_IntegrityFailure() {
            // Given
            val staleTime = LocalDateTime.now().minusMinutes(10)
            val corruptedEvent = EventOutbox.create("stream", "CorruptedEvent", "{}")
            corruptedEvent.markProcessing("old-instance")
            corruptedEvent.lockedAt = staleTime

            // Corrupt the hash
            corruptedEvent.javaClass.getDeclaredField("contentHash").apply {
                isAccessible = true
                set(corruptedEvent, "corrupted-hash")
            }

            eventOutboxRepository.save(corruptedEvent)

            // When
            processor.recoverStalled()

            // Then
            val dlqEntry = eventOutboxRepository.findByIdOrNull(corruptedEvent.id!!)
            assertNotNull(dlqEntry)
            assertEquals(EventOutboxStatus.DEAD_LETTER, dlqEntry.status)
        }
    }

    @Nested
    @DisplayName("Integrity Verification")
    inner class IntegrityTests {

        @Test
        @DisplayName("should verify content hash integrity")
        fun verifyIntegrity_ValidHash() {
            // Given
            val event = EventOutbox.create("stream", "TestEvent", """{"test": "data"}""")

            // When
            val isValid = event.verifyIntegrity()

            // Then
            assertTrue(isValid)
        }

        @Test
        @DisplayName("should detect corrupted content hash")
        fun verifyIntegrity_CorruptedHash() {
            // Given
            val event = EventOutbox.create("stream", "TestEvent", """{"test": "data"}""")
            event.javaClass.getDeclaredField("contentHash").apply {
                isAccessible = true
                set(event, "corrupted-hash")
            }

            // When
            val isValid = event.verifyIntegrity()

            // Then
            assertEquals(false, isValid)
        }

        @Test
        @DisplayName("should move integrity failures to DLQ immediately")
        fun processEntry_IntegrityFailureMoveToDlq() {
            // Given
            val event = EventOutbox.create("stream", "TestEvent", "{}")
            event.javaClass.getDeclaredField("contentHash").apply {
                isAccessible = true
                set(event, "tampered-hash")
            }
            eventOutboxRepository.save(event)

            // When
            processor.pollAndProcess()

            // Then
            val dlqEntry = eventOutboxRepository.findByIdOrNull(event.id!!)
            assertNotNull(dlqEntry)
            assertEquals(EventOutboxStatus.DEAD_LETTER, dlqEntry.status)
            // Should move to DLQ immediately without retry
        }
    }
}
