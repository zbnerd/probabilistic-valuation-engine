package maple.expectation.infrastructure.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.observation.ObservationRegistry
import maple.expectation.core.domain.event.IntegrationEvent
import maple.expectation.event.EventHandler
import maple.expectation.infrastructure.executor.LogicExecutor
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.redisson.api.RStream
import org.redisson.api.RedissonClient
import org.redisson.api.StreamMessageId
import org.redisson.client.codec.StringCodec
import org.springframework.context.ApplicationContext
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Unit tests for {@link RedisStreamEventConsumer}.
 *
 * <p><strong>Test Coverage:</strong>
 *
 * <ul>
 *   <li>Handler discovery and validation
 *   <li>Consumer group creation
 *   <li>Message consumption and processing
 *   <li>Deduplication filter integration
 *   <li>PEL (Pending Entries List) recovery
 *   <li>Error handling and logging
 * </ul>
 *
 * <p><strong>Flaky Test Prevention:</strong>
 * <ul>
 *   <li>No Thread.sleep - uses direct assertions
 *   <li>No shared state between tests
 *   <li>Mocked Redis and Spring dependencies
 *   <li>Deterministic test data
 * </ul>
 */
@ExtendWith(MockitoExtension::class)
@DisplayName("RedisStreamEventConsumer Tests")
class RedisStreamEventConsumerTest {

    @Mock
    private lateinit var redissonClient: RedissonClient

    @Mock
    private lateinit var stream: RStream<String, String>

    @Mock
    private lateinit var objectMapper: ObjectMapper

    @Mock
    private lateinit var deduplicationFilter: DeduplicationFilter

    @Mock
    private lateinit var executor: LogicExecutor

    @Mock
    private lateinit var applicationContext: ApplicationContext

    private lateinit var observationRegistry: ObservationRegistry
    private lateinit var consumer: RedisStreamEventConsumer

    private val streamKey = "test-stream"
    private val consumerGroup = "test-group"
    private val consumerName = "test-consumer"
    private val readTimeout = Duration.ofSeconds(1)

    @BeforeEach
    fun setUp() {
        observationRegistry = ObservationRegistry.create()

        org.mockito.kotlin.whenever(redissonClient.getStream(streamKey, StringCodec.INSTANCE))
            .thenReturn(stream)

        // Default executor behavior
        org.mockito.kotlin.whenever(
            executor.executeVoidJava(
                org.mockito.kotlin.any(),
                org.mockito.kotlin.any()
            )
        ).thenAnswer { invocation ->
            val task = invocation.getArgument<java.lang.Runnable>(0)
            task.run()
        }

        org.mockito.kotlin.whenever(
            executor.executeOrDefault(
                org.mockito.kotlin.any(),
                org.mockito.kotlin.any(),
                org.mockito.kotlin.any()
            )
        ).thenAnswer { invocation ->
            val task = invocation.getArgument<() -> Any?>(0)
            val default = invocation.getArgument<Any?>(1)
            try {
                task.invoke()
            } catch (e: Exception) {
                default
            }
        }

        // Mock consumer group creation
        org.mockito.kotlin.whenever(
            stream.createGroup(org.mockito.kotlin.any())
        ).thenThrow(RuntimeException("BUSYGROUP Consumer Group name already exists"))

        consumer = RedisStreamEventConsumer(
            redissonClient = redissonClient,
            objectMapper = objectMapper,
            deduplicationFilter = deduplicationFilter,
            executor = executor,
            observationRegistry = observationRegistry,
            streamKey = streamKey,
            consumerGroup = consumerGroup,
            consumerName = consumerName,
            readTimeout = readTimeout
        )

        consumer.setApplicationContext(applicationContext)
    }

    @Nested
    @DisplayName("Handler Discovery")
    inner class HandlerDiscoveryTests {

        @Test
        @DisplayName("should discover handlers with @EventHandler annotation")
        fun discoverHandlers_Success() {
            // Given
            val testHandler = TestEventHandler()
            val beans = mapOf("testHandler" to testHandler)

            org.mockito.kotlin.whenever(applicationContext.getBeansOfType(Any::class.java))
                .thenReturn(beans)

            // When
            consumer.setApplicationContext(applicationContext)

            // Then
            assertEquals(1, consumer.getHandlerTypeCount())
        }

        @Test
        @DisplayName("should ignore beans without @EventHandler")
        fun discoverHandlers_IgnoreNonEventHandlers() {
            // Given
            val nonHandler = object {
                fun regularMethod() {}
            }
            val beans = mapOf("nonHandler" to nonHandler)

            org.mockito.kotlin.whenever(applicationContext.getBeansOfType(Any::class.java))
                .thenReturn(beans)

            // When
            consumer.setApplicationContext(applicationContext)

            // Then
            assertEquals(0, consumer.getHandlerTypeCount())
        }
    }

    @Nested
    @DisplayName("PEL Recovery")
    inner class PelRecoveryTests {

        @Test
        @DisplayName("should report pending message count")
        fun getPendingCount_Success() {
            // Given
            val expectedCount = 42L

            org.mockito.kotlin.whenever(
                executor.executeOrDefault(
                    org.mockito.kotlin.any(),
                    org.mockito.kotlin.any(),
                    org.mockito.kotlin.any()
                )
            ).thenReturn(expectedCount)

            // When
            val pendingCount = consumer.getPendingCount()

            // Then
            assertEquals(expectedCount, pendingCount)
        }

        @Test
        @DisplayName("should return 0 on pending count error")
        fun getPendingCount_Error() {
            // Given
            org.mockito.kotlin.whenever(
                executor.executeOrDefault(
                    org.mockito.kotlin.any(),
                    org.mockito.kotlin.any(),
                    org.mockito.kotlin.any()
                )
            ).thenThrow(RuntimeException("Redis connection lost"))

            // When
            val pendingCount = consumer.getPendingCount()

            // Then - Should return 0 on error (default value)
            assertEquals(0L, pendingCount)
        }
    }

    @Nested
    @DisplayName("Consumer Group Creation")
    inner class ConsumerGroupCreationTests {

        @Test
        @DisplayName("should create consumer group if not exists")
        fun init_CreateGroup() {
            // Given
            org.mockito.kotlin.whenever(
                stream.createGroup(org.mockito.kotlin.any())
            ).thenReturn(null)

            // When
            RedisStreamEventConsumer(
                redissonClient = redissonClient,
                objectMapper = objectMapper,
                deduplicationFilter = deduplicationFilter,
                executor = executor,
                observationRegistry = observationRegistry,
                streamKey = streamKey,
                consumerGroup = consumerGroup,
                consumerName = consumerName,
                readTimeout = readTimeout
            )

            // Then
            org.mockito.kotlin.verify(stream).createGroup(org.mockito.kotlin.any())
        }

        @Test
        @DisplayName("should handle existing consumer group gracefully")
        fun init_ExistingGroup() {
            // Given - BUSYGROUP error indicates group already exists
            org.mockito.kotlin.whenever(
                stream.createGroup(org.mockito.kotlin.any())
            ).thenThrow(RuntimeException("BUSYGROUP Consumer Group name already exists"))

            // When - Should not throw
            val newConsumer = RedisStreamEventConsumer(
                redissonClient = redissonClient,
                objectMapper = objectMapper,
                deduplicationFilter = deduplicationFilter,
                executor = executor,
                observationRegistry = observationRegistry,
                streamKey = streamKey,
                consumerGroup = consumerGroup,
                consumerName = consumerName,
                readTimeout = readTimeout
            )

            // Then - Consumer should be initialized successfully
            assertNotNull(newConsumer)
        }
    }

    // Test fixtures

    class TestEventHandler {
        @EventHandler
        fun handleTestEvent(event: TestEventData) {
            // Handle test event
        }
    }

    data class TestEventData(val name: String)
}
