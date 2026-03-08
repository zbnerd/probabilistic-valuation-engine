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
            ).thenThrow(RuntimeException("Disk full"))

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
