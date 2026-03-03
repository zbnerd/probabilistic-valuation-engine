package maple.expectation.service.v5.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Map;
import maple.expectation.common.function.ThrowingSupplier;
import maple.expectation.core.event.ExpectationCalculationCompletedEvent;
import maple.expectation.infrastructure.executor.CheckedLogicExecutor;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.executor.function.ThrowingRunnable;
import maple.expectation.infrastructure.mongodb.CharacterValuationView;
import maple.expectation.infrastructure.mongodb.CharacterViewQueryService;
import maple.expectation.service.v5.event.ViewTransformer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.redisson.client.codec.StringCodec;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * V5 CQRS: MongoDB Sync Worker Unit Tests
 *
 * <h3>Test Scope</h3>
 *
 * <ul>
 *   <li>Stream initialization strategies (new stream, existing stream, existing group)
 *   <li>Message processing flow (deserialize -> transform -> upsert -> ACK)
 *   <li>Error handling (JSON deserialization failure, MongoDB failure)
 *   <li>Idempotency verification (duplicate message handling)
 *   <li>Metrics emission (processed counter, error counter)
 * </ul>
 *
 * <h3>Test Strategy</h3>
 *
 * Uses Mockito mocks for infrastructure components (Redisson, MongoDB, LogicExecutor). Focuses on
 * worker logic and CQRS flow validation.
 *
 * <h3>Test Case: 아델</h3>
 *
 * Uses "아델" as the primary test user IGN to verify Korean character handling.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
@DisplayName("V5: MongoDB Sync Worker Tests")
class MongoDBSyncWorkerTest {

  private static final String TEST_IGN = "아델";
  private static final String TEST_TASK_ID = "test-task-123";
  private static final String STREAM_KEY = "character-sync";
  private static final String CONSUMER_GROUP = "mongodb-sync-group";

  @Mock private RedissonClient redissonClient;

  @Mock private CharacterViewQueryService queryService;

  @Mock private LogicExecutor executor;

  @Mock private CheckedLogicExecutor checkedExecutor;

  @Mock private ViewTransformer viewTransformer;

  @Mock private ObjectMapper objectMapper;

  @Mock private MeterRegistry meterRegistry;

  @Mock private RStream<String, String> mockStream;

  @Mock private Counter processedCounter;

  @Mock private Counter errorCounter;

  private MongoDBSyncWorker worker;

  @BeforeEach
  void setUp() {
    // Lenient stubs for meter registry - not all tests use these
    lenient().when(meterRegistry.counter(any(String.class))).thenReturn(processedCounter);
    lenient().when(meterRegistry.counter("mongodb.sync.errors")).thenReturn(errorCounter);

    // Mock executor to actually execute the tasks (for stream initialization) - lenient since not
    // all tests use it
    lenient()
        .doAnswer(
            invocation -> {
              ThrowingRunnable task = invocation.getArgument(0);
              task.run();
              return null;
            })
        .when(executor)
        .executeVoid(any(ThrowingRunnable.class), any(TaskContext.class));

    // Mock executeVoidJava for processMessage (uses Runnable instead of ThrowingRunnable)
    lenient()
        .doAnswer(
            invocation -> {
              Runnable task = invocation.getArgument(0);
              task.run();
              return null;
            })
        .when(executor)
        .executeVoidJava(any(Runnable.class), any(TaskContext.class));

    // Mock executeOrCatch with Function1 recovery - lenient since not all tests use it
    lenient()
        .doAnswer(
            invocation -> {
              ThrowingSupplier<?> task = invocation.getArgument(0);
              try {
                return task.get();
              } catch (Throwable e) {
                kotlin.jvm.functions.Function1<?, ?> recovery = invocation.getArgument(1);
                return ((kotlin.jvm.functions.Function1<Throwable, Object>) recovery).invoke(e);
              }
            })
        .when(executor)
        .executeOrCatch(
            any(ThrowingSupplier.class),
            any(kotlin.jvm.functions.Function1.class),
            any(TaskContext.class));

    // Lenient mock for checkedExecutor.executeUncheckedVoid - not all tests use deserialization
    lenient()
        .doAnswer(
            invocation -> {
              maple.expectation.infrastructure.executor.function.CheckedRunnable task =
                  invocation.getArgument(0);
              try {
                task.run();
              } catch (Throwable e) {
                java.util.function.Function<Throwable, RuntimeException> translator =
                    invocation.getArgument(2);
                throw translator.apply(e);
              }
              return null;
            })
        .when(checkedExecutor)
        .executeUncheckedVoid(
            any(maple.expectation.infrastructure.executor.function.CheckedRunnable.class),
            any(TaskContext.class),
            any(java.util.function.Function.class));

    worker =
        new MongoDBSyncWorker(
            redissonClient,
            queryService,
            executor,
            checkedExecutor,
            viewTransformer,
            objectMapper,
            meterRegistry);

    // Lenient stub for stream - not all tests use it
    lenient()
        .doReturn(mockStream)
        .when(redissonClient)
        .getStream(eq(STREAM_KEY), any(StringCodec.class));
  }

  /**
   * Ensure worker is stopped after each test to prevent thread leaks. Tests that call start() must
   * be paired with stop().
   */
  @org.junit.jupiter.api.AfterEach
  void tearDown() {
    try {
      if (worker != null) {
        worker.stop();
      }
    } catch (Exception e) {
      // Ignore cleanup errors
    }
  }

  @Test
  @DisplayName("Stream initialization: Worker starts successfully")
  void testStreamInitialization_WorkerStartsSuccessfully() {
    // Worker should start without exception regardless of stream state
    worker.start();

    // Verify worker is running
    assertThat(ReflectionTestUtils.getField(worker, "running")).isEqualTo(true);
  }

  @Test
  @DisplayName("Stream initialization: Existing stream with group does not create group")
  void testStreamInitialization_ExistingStreamWithGroup_DoesNotCreateGroup() {
    // Mock: stream exists and has group (readGroup succeeds)
    lenient().when(mockStream.isExists()).thenReturn(true);
    lenient()
        .doReturn(Map.of())
        .when(mockStream)
        .readGroup(anyString(), anyString(), any(StreamReadGroupArgs.class));

    worker.start();

    verify(mockStream, never()).createGroup(any());
  }

  @Test
  @DisplayName("Message processing: Valid message deserializes and upserts to MongoDB")
  void testMessageProcessing_ValidMessage_UpsertsToMongoDB() throws Exception {
    String payloadJson = createTestPayloadJson();
    StreamMessageId messageId = new StreamMessageId(1234567890L, 0L);
    Map<String, String> messageData = Map.of("data", payloadJson);

    ExpectationCalculationCompletedEvent event = createTestEvent();
    CharacterValuationView view = createTestView();

    when(objectMapper.readValue(payloadJson, ExpectationCalculationCompletedEvent.class))
        .thenReturn(event);
    when(viewTransformer.toDocument(event)).thenReturn(view);

    invokeProcessMessage(worker, messageId, messageData);

    verify(viewTransformer).toDocument(event);
    verify(queryService).upsert(view);
  }

  @Test
  @DisplayName("Message processing: Empty payload logs warning and continues")
  void testMessageProcessing_EmptyPayload_LogsWarning() {
    StreamMessageId messageId = new StreamMessageId(1234567890L, 0L);
    Map<String, String> messageData = Map.of("otherKey", "value");

    invokeProcessMessage(worker, messageId, messageData);

    verify(viewTransformer, never()).toDocument(any());
    verify(queryService, never()).upsert(any());
  }

  @Test
  @DisplayName("Message processing: Deserialization failure increments error counter")
  void testMessageProcessing_DeserializationFailure_IncrementsErrorCounter() throws Exception {
    String payloadJson = "invalid-json";
    StreamMessageId messageId = new StreamMessageId(1234567890L, 0L);
    Map<String, String> messageData = Map.of("data", payloadJson);

    when(objectMapper.readValue(payloadJson, ExpectationCalculationCompletedEvent.class))
        .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("Invalid JSON") {});

    // The exception gets wrapped in InternalSystemException and caught by processSingleMessage
    // which increments errorCounter
    try {
      invokeProcessSingleMessageWithAck(worker, mockStream, messageId, messageData);
    } catch (Exception e) {
      // Expected - the exception is thrown after incrementing error counter
    }

    verify(errorCounter).increment();
  }

  @Test
  @DisplayName("Idempotency: Same messageId upserts to same document (no duplicate)")
  void testIdempotency_SameMessageId_NoDuplicates() throws Exception {
    String payloadJson = createTestPayloadJson();
    StreamMessageId messageId = new StreamMessageId(1234567890L, 0L);
    Map<String, String> messageData = Map.of("data", payloadJson);

    ExpectationCalculationCompletedEvent event = createTestEvent();
    CharacterValuationView view =
        new CharacterValuationView(
            TEST_IGN + ":" + TEST_TASK_ID,
            TEST_IGN,
            null, // messageId
            null, // characterOcid
            null, // characterClass
            null, // characterLevel
            null, // calculatedAt
            null, // lastApiSyncAt
            null, // version
            null, // totalExpectedCost
            null, // maxPresetNo
            null, // presets
            null // fromCache
            );

    when(objectMapper.readValue(payloadJson, ExpectationCalculationCompletedEvent.class))
        .thenReturn(event);
    when(viewTransformer.toDocument(event)).thenReturn(view);

    invokeProcessMessage(worker, messageId, messageData);
    invokeProcessMessage(worker, messageId, messageData);

    verify(queryService, times(2)).upsert(view);
  }

  @Test
  @DisplayName("Korean character handling: 아델 IGN processes correctly")
  void testKoreanCharacterHandling_AdelIGN_ProcessesCorrectly() throws Exception {
    String payloadJson = createTestPayloadJson();
    StreamMessageId messageId = new StreamMessageId(1234567890L, 0L);
    Map<String, String> messageData = Map.of("data", payloadJson);

    ExpectationCalculationCompletedEvent event = createTestEvent();
    CharacterValuationView view =
        new CharacterValuationView(
            TEST_IGN + ":" + TEST_TASK_ID,
            TEST_IGN,
            null, // messageId
            null, // characterOcid
            null, // characterClass
            null, // characterLevel
            null, // calculatedAt
            null, // lastApiSyncAt
            null, // version
            null, // totalExpectedCost
            null, // maxPresetNo
            null, // presets
            null // fromCache
            );

    when(objectMapper.readValue(payloadJson, ExpectationCalculationCompletedEvent.class))
        .thenReturn(event);
    when(viewTransformer.toDocument(event)).thenReturn(view);

    invokeProcessMessage(worker, messageId, messageData);

    assertThat(view.getUserIgn()).isEqualTo(TEST_IGN);
    verify(queryService).upsert(view);
  }

  @Test
  @DisplayName("Worker lifecycle: Stop gracefully interrupts thread")
  void testWorkerLifecycle_Stop_GracefullyInterrupts() {
    worker.start();
    worker.stop();

    assertThat(ReflectionTestUtils.getField(worker, "running")).isEqualTo(false);
  }

  @Test
  @DisplayName("Metrics: Successful processing increments processed counter")
  void testMetrics_SuccessfulProcessing_IncrementsProcessedCounter() throws Exception {
    String payloadJson = createTestPayloadJson();
    StreamMessageId messageId = new StreamMessageId(1234567890L, 0L);
    Map<String, String> messageData = Map.of("data", payloadJson);

    ExpectationCalculationCompletedEvent event = createTestEvent();
    CharacterValuationView view = createTestView();

    when(objectMapper.readValue(payloadJson, ExpectationCalculationCompletedEvent.class))
        .thenReturn(event);
    when(viewTransformer.toDocument(event)).thenReturn(view);

    invokeProcessSingleMessageWithAck(worker, mockStream, messageId, messageData);

    verify(processedCounter).increment();
  }

  private void invokeProcessMessage(
      MongoDBSyncWorker worker, StreamMessageId messageId, Map<String, String> data) {
    try {
      var method =
          MongoDBSyncWorker.class.getDeclaredMethod(
              "processMessage", StreamMessageId.class, Map.class);
      method.setAccessible(true);
      method.invoke(worker, messageId, data);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private void invokeProcessSingleMessageWithAck(
      MongoDBSyncWorker worker,
      RStream<String, String> stream,
      StreamMessageId messageId,
      Map<String, String> data) {
    try {
      var method =
          MongoDBSyncWorker.class.getDeclaredMethod(
              "processSingleMessage", RStream.class, StreamMessageId.class, Map.class);
      method.setAccessible(true);
      method.invoke(worker, stream, messageId, data);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private String createTestPayloadJson() {
    return "{\"userIgn\":\"아델\",\"taskId\":\"" + TEST_TASK_ID + "\"}";
  }

  private ExpectationCalculationCompletedEvent createTestEvent() {
    return ExpectationCalculationCompletedEvent.builder()
        .taskId(TEST_TASK_ID)
        .userIgn(TEST_IGN)
        .characterOcid("test-ocid")
        .characterClass("Pathfinder")
        .characterLevel(275)
        .calculatedAt(Instant.now().toString())
        .totalExpectedCost("1000000")
        .maxPresetNo(1)
        .payload("{}")
        .build();
  }

  private CharacterValuationView createTestView() {
    return new CharacterValuationView(
        TEST_IGN + ":" + TEST_TASK_ID,
        TEST_IGN,
        null, // messageId
        "test-ocid",
        "Pathfinder",
        275,
        null, // calculatedAt
        null, // lastApiSyncAt
        null, // version
        1000000L,
        1, // maxPresetNo
        null, // presets
        null // fromCache
        );
  }
}
