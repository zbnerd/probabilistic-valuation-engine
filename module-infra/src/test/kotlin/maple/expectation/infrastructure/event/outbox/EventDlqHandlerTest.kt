package maple.expectation.infrastructure.event.outbox

import maple.expectation.core.port.out.ShutdownDataPersistencePort
import maple.expectation.domain.v2.EventOutbox
import maple.expectation.domain.v2.EventOutbox.EventOutboxStatus
import maple.expectation.infrastructure.alert.StatelessAlertService
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.metrics.EventOutboxMetrics
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Unit tests for {@link EventDlqHandler}.
 *
 * <p><strong>Test Coverage:</strong>
 *
 * <ul>
 *   <li>Successful DLQ handling with file backup
 *   <li>File backup failure with critical alert
 *   <li>Alert service invocation with correct parameters
 *   <li>Multiple DLQ scenarios
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
@DisplayName("EventDlqHandler Tests")
class EventDlqHandlerTest {

    @Mock
    private lateinit var fileBackupService: ShutdownDataPersistencePort

    @Mock
    private lateinit var statelessAlertService: StatelessAlertService

    @Mock
    private lateinit var executor: LogicExecutor

    @Mock
    private lateinit var metrics: EventOutboxMetrics

    private lateinit var dlqHandler: EventDlqHandler

    @BeforeEach
    fun setUp() {
        dlqHandler = EventDlqHandler(
            fileBackupService = fileBackupService,
            statelessAlertService = statelessAlertService,
            executor = executor,
            metrics = metrics
        )
    }

    @Nested
    @DisplayName("handleDeadLetter()")
    inner class HandleDeadLetterTests {

        @Test
        @DisplayName("should successfully backup dead letter to file")
        fun handleDeadLetter_Success() {
            // Given
            val entry = createTestEventOutbox(
                id = 1L,
                eventType = "TestEvent",
                targetStream = "test-stream",
                payload = """{"data": "test"}"""
            )
            val reason = "Max retries exceeded"

            org.mockito.kotlin.whenever(
                executor.executeOrCatch(
                    org.mockito.kotlin.any(),
                    org.mockito.kotlin.any(),
                    org.mockito.kotlin.any()
                )
            ).thenAnswer { invocation ->
                val task = invocation.getArgument<() -> Unit>(0)
                task.invoke()
            }

            // When
            dlqHandler.handleDeadLetter(entry, reason)

            // Then
            org.mockito.kotlin.verify(fileBackupService).appendOutboxEntry(
                eventId = "1",
                payload = """{"data": "test"}"""
            )
        }

        @Test
        @DisplayName("should send critical alert when file backup fails")
        fun handleDeadLetter_FileBackupFailure() {
            // Given
            val entry = createTestEventOutbox(
                id = 1L,
                eventType = "TestEvent",
                targetStream = "test-stream",
                payload = """{"data": "test"}"""
            )
            val reason = "Max retries exceeded"
            val fileException = RuntimeException("Disk full")

            org.mockito.kotlin.whenever(
                executor.executeOrCatch(
                    org.mockito.kotlin.any(),
                    org.mockito.kotlin.any(),
                    org.mockito.kotlin.any()
                )
            ).thenAnswer { invocation ->
                val task = invocation.getArgument<() -> Unit>(0)
                val errorHandler = invocation.getArgument<(Throwable) -> Unit>(1)
                try {
                    task.invoke()
                } catch (e: Exception) {
                    errorHandler.invoke(e)
                }
            }

            org.mockito.kotlin.whenever(
                fileBackupService.appendOutboxEntry(
                    org.mockito.kotlin.anyString(),
                    org.mockito.kotlin.anyString()
                )
            ).thenThrow(fileException)

            // When
            dlqHandler.handleDeadLetter(entry, reason)

            // Then
            val titleCaptor = kotlin.argumentCaptor<String>()
            val descriptionCaptor = kotlin.argumentCaptor<String>()
            val exceptionCaptor = kotlin.argumentCaptor<Throwable>()

            org.mockito.kotlin.verify(statelessAlertService).sendCritical(
                title = titleCaptor.capture(),
                description = descriptionCaptor.capture(),
                cause = exceptionCaptor.capture()
            )

            assertEquals("EVENT OUTBOX CRITICAL FAILURE", titleCaptor.firstValue)
            assertTrue(descriptionCaptor.firstValue.contains("EventId: 1"))
            assertTrue(descriptionCaptor.firstValue.contains("EventType: TestEvent"))
            assertTrue(descriptionCaptor.firstValue.contains("Reason: Max retries exceeded"))
            assertEquals(fileException, exceptionCaptor.firstValue)
        }

        @Test
        @DisplayName("should handle entries with null ID")
        fun handleDeadLetter_NullId() {
            // Given
            val entry = createTestEventOutbox(
                id = null,
                eventType = "TestEvent",
                targetStream = "test-stream"
            )
            val reason = "Processing failed"

            org.mockito.kotlin.whenever(
                executor.executeOrCatch(
                    org.mockito.kotlin.any(),
                    org.mockito.kotlin.any(),
                    org.mockito.kotlin.any()
                )
            ).thenAnswer { invocation ->
                val task = invocation.getArgument<() -> Unit>(0)
                task.invoke()
            }

            // When
            dlqHandler.handleDeadLetter(entry, reason)

            // Then
            org.mockito.kotlin.verify(fileBackupService).appendOutboxEntry(
                eventId = "unknown",
                payload = org.mockito.kotlin.anyString()
            )
        }

        @Test
        @DisplayName("should handle entries with null payload")
        fun handleDeadLetter_NullPayload() {
            // Given
            val entry = createTestEventOutbox(
                id = 1L,
                eventType = "TestEvent",
                targetStream = "test-stream",
                payload = null
            )
            val reason = "Invalid payload"

            org.mockito.kotlin.whenever(
                executor.executeOrCatch(
                    org.mockito.kotlin.any(),
                    org.mockito.kotlin.any(),
                    org.mockito.kotlin.any()
                )
            ).thenAnswer { invocation ->
                val task = invocation.getArgument<() -> Unit>(0)
                task.invoke()
            }

            // When
            dlqHandler.handleDeadLetter(entry, reason)

            // Then
            org.mockito.kotlin.verify(fileBackupService).appendOutboxEntry(
                eventId = "1",
                payload = "{}"
            )
        }

        @Test
        @DisplayName("should handle multiple DLQ entries sequentially")
        fun handleDeadLetter_MultipleEntries() {
            // Given
            val entry1 = createTestEventOutbox(id = 1L, eventType = "Event1")
            val entry2 = createTestEventOutbox(id = 2L, eventType = "Event2")
            val entry3 = createTestEventOutbox(id = 3L, eventType = "Event3")

            org.mockito.kotlin.whenever(
                executor.executeOrCatch(
                    org.mockito.kotlin.any(),
                    org.mockito.kotlin.any(),
                    org.mockito.kotlin.any()
                )
            ).thenAnswer { invocation ->
                val task = invocation.getArgument<() -> Unit>(0)
                task.invoke()
            }

            // When
            dlqHandler.handleDeadLetter(entry1, "Error 1")
            dlqHandler.handleDeadLetter(entry2, "Error 2")
            dlqHandler.handleDeadLetter(entry3, "Error 3")

            // Then
            org.mockito.kotlin.verify(fileBackupService, org.mockito.kotlin.times(3))
                .appendOutboxEntry(
                    org.mockito.kotlin.anyString(),
                    org.mockito.kotlin.anyString()
                )
        }
    }

    @Nested
    @DisplayName("Alert Content Validation")
    inner class AlertContentTests {

        @Test
        @DisplayName("should include all relevant information in alert")
        fun handleDeadLetter_AlertContentComplete() {
            // Given
            val entry = createTestEventOutbox(
                id = 123L,
                eventType = "CharacterCreated",
                targetStream = "character-sync",
                payload = """{"characterId": 456, "name": "Test"}"""
            )
            val reason = "Redis Stream connection timeout"

            org.mockito.kotlin.whenever(
                executor.executeOrCatch(
                    org.mockito.kotlin.any(),
                    org.mockito.kotlin.any(),
                    org.mockito.kotlin.any()
                )
            ).thenAnswer { invocation ->
                val task = invocation.getArgument<() -> Unit>(0)
                val errorHandler = invocation.getArgument<(Throwable) -> Unit>(1)
                try {
                    task.invoke()
                } catch (e: Exception) {
                    errorHandler.invoke(e)
                }
            }

            val fileException = IOException("Disk write failed")
            org.mockito.kotlin.whenever(
                fileBackupService.appendOutboxEntry(
                    org.mockito.kotlin.anyString(),
                    org.mockito.kotlin.anyString()
                )
            ).thenThrow(fileException)

            // When
            dlqHandler.handleDeadLetter(entry, reason)

            // Then
            val descriptionCaptor = kotlin.argumentCaptor<String>()
            org.mockito.kotlin.verify(statelessAlertService).sendCritical(
                org.mockito.kotlin.anyString(),
                description = descriptionCaptor.capture(),
                org.mockito.kotlin.any()
            )

            val description = descriptionCaptor.firstValue
            assertTrue(description.contains("EventId: 123"))
            assertTrue(description.contains("EventType: CharacterCreated"))
            assertTrue(description.contains("Reason: Redis Stream connection timeout"))
            assertTrue(description.contains("Manual intervention required"))
        }

        @Test
        @DisplayName("should truncate long error messages in alert")
        fun handleDeadLetter_LongErrorMessage() {
            // Given
            val entry = createTestEventOutbox(id = 1L)
            val longReason = "A".repeat(1000) // Very long error message

            org.mockito.kotlin.whenever(
                executor.executeOrCatch(
                    org.mockito.kotlin.any(),
                    org.mockito.kotlin.any(),
                    org.mockito.kotlin.any()
                )
            ).thenAnswer { invocation ->
                val task = invocation.getArgument<() -> Unit>(0)
                val errorHandler = invocation.getArgument<(Throwable) -> Unit>(1)
                try {
                    task.invoke()
                } catch (e: Exception) {
                    errorHandler.invoke(e)
                }
            }

            org.mockito.kotlin.whenever(
                fileBackupService.appendOutboxEntry(
                    org.mockito.kotlin.anyString(),
                    org.mockito.kotlin.anyString()
                )
            ).thenThrow(RuntimeException("Failed"))

            // When
            dlqHandler.handleDeadLetter(entry, longReason)

            // Then
            val descriptionCaptor = kotlin.argumentCaptor<String>()
            org.mockito.kotlin.verify(statelessAlertService).sendCritical(
                org.mockito.kotlin.anyString(),
                description = descriptionCaptor.capture(),
                org.mockito.kotlin.any()
            )

            // Description should contain the reason (not truncated in alert, only in DB)
            assertTrue(descriptionCaptor.firstValue.contains(longReason))
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCaseTests {

        @Test
        @DisplayName("should handle empty event type")
        fun handleDeadLetter_EmptyEventType() {
            // Given
            val entry = createTestEventOutbox(
                id = 1L,
                eventType = "",
                targetStream = ""
            )
            val reason = "Empty event type"

            org.mockito.kotlin.whenever(
                executor.executeOrCatch(
                    org.mockito.kotlin.any(),
                    org.mockito.kotlin.any(),
                    org.mockito.kotlin.any()
                )
            ).thenAnswer { invocation ->
                val task = invocation.getArgument<() -> Unit>(0)
                task.invoke()
            }

            // When
            dlqHandler.handleDeadLetter(entry, reason)

            // Then
            org.mockito.kotlin.verify(fileBackupService).appendOutboxEntry(
                eventId = "1",
                payload = org.mockito.kotlin.anyString()
            )
        }

        @Test
        @DisplayName("should handle special characters in payload")
        fun handleDeadLetter_SpecialCharactersInPayload() {
            // Given
            val specialPayload = """{"data": "Test with \"quotes\" and \n newlines \t tabs"}"""
            val entry = createTestEventOutbox(
                id = 1L,
                payload = specialPayload
            )
            val reason = "Special characters"

            org.mockito.kotlin.whenever(
                executor.executeOrCatch(
                    org.mockito.kotlin.any(),
                    org.mockito.kotlin.any(),
                    org.mockito.kotlin.any()
                )
            ).thenAnswer { invocation ->
                val task = invocation.getArgument<() -> Unit>(0)
                task.invoke()
            }

            // When
            dlqHandler.handleDeadLetter(entry, reason)

            // Then
            org.mockito.kotlin.verify(fileBackupService).appendOutboxEntry(
                eventId = "1",
                payload = specialPayload
            )
        }
    }

    // Helper methods

    private fun createTestEventOutbox(
        id: Long? = 1L,
        eventType: String = "TestEvent",
        targetStream: String = "test-stream",
        payload: String? = """{"data": "test"}""",
        status: EventOutboxStatus = EventOutboxStatus.DEAD_LETTER
    ): EventOutbox {
        val outbox = EventOutbox.create(targetStream, eventType, payload ?: "{}")
        outbox.id = id
        outbox.status = status
        return outbox
    }
}
