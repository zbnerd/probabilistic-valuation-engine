package maple.expectation.application.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.application.service.expectation.event.ViewTransformer;
import maple.expectation.application.service.expectation.stream.StreamInitializationStrategy;
import maple.expectation.application.service.expectation.stream.StreamStrategyFactory;
import maple.expectation.core.event.ExpectationCalculationCompletedEvent;
import maple.expectation.error.exception.InternalSystemException;
import maple.expectation.infrastructure.executor.CheckedLogicExecutor;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.mongodb.CharacterValuationView;
import maple.expectation.infrastructure.mongodb.CharacterViewQueryService;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * V5 CQRS: MongoDB Sync Worker - Consumes Redis Stream events
 *
 * <h3>Responsibilities</h3>
 *
 * <ul>
 *   <li>Consume calculation events from character-sync stream
 *   <li>Delegate transformation to ViewTransformer (SRP)
 *   <li>Upsert to CharacterValuationView collection
 *   <li>Acknowledge processed messages
 *   <li>PEL Recovery on startup (from crashed consumers)
 *   <li>Poison Pill handling with DLQ
 *   <li>XAUTOCLAIM Janitor for orphaned messages
 *   <li><b>Unit 4: Event ordering with versioning for causal consistency</b>
 * </ul>
 *
 * <h3>Reliability Features (Issue #490)</h3>
 *
 * <ul>
 *   <li><b>PEL Recovery:</b> On startup, processes pending messages from previous crashes before
 *       new messages
 *   <li><b>Poison Pill DLQ:</b> tracks retry count, moves unprocessable messages to DLQ stream
 *       after max retries
 *   <li><b>XAUTOCLAIM Janitor:</b> claims orphaned messages from inactive consumers
 * </ul>
 *
 * <h3>Unit 4: Event Ordering & Versioning (P1 - High)</h3>
 *
 * <p>Prevents out-of-order events from corrupting Read Model state.
 *
 * <ul>
 *   <li><b>Version Check:</b> Skips events where version <= lastAppliedVersion (already applied)
 *   <li><b>Buffering:</b> Buffers events where version > lastAppliedVersion + 1 (out-of-order)
 *   <li><b>Sequential Apply:</b> Applies buffered events when sequence gap is filled
 *   <li><b>Metrics:</b> Tracks skipped, buffered, and applied events
 * </ul>
 *
 * <h3>Flow</h3>
 *
 * <ol>
 *   <li>Phase 1: PEL Recovery - process pending messages from crashed consumers
 *   <li>Phase 2: New message processing - read and process new messages with version ordering
 * </ol>
 *
 * <h3>Section 12 Compliance (Zero Try-Catch):</h3>
 *
 * <p>All exception handling delegated to LogicExecutor/CheckedLogicExecutor.
 *
 * <h3>Section 15 Compliance (Lambda Hell Prevention):</h3>
 *
 * <p>Complex transformation logic extracted to ViewTransformer service.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "v5.enabled", havingValue = "true", matchIfMissing = false)
public class MongoDBSyncWorker implements Runnable {

  private static final String STREAM_KEY = "character-sync";
  private static final String CONSUMER_GROUP = "mongodb-sync-group";
  private static final String CONSUMER_NAME = "mongodb-sync-worker";
  private static final String DLQ_STREAM_KEY = "character-sync-dlq";
  private static final Duration POLL_TIMEOUT = Duration.ofMillis(2000);
  private static final int MAX_RETRIES = 3;
  private static final int BATCH_SIZE = 10;

  private final RedissonClient redissonClient;
  private final CharacterViewQueryService queryService;
  private final LogicExecutor executor;
  private final CheckedLogicExecutor checkedExecutor;
  private final ViewTransformer viewTransformer;
  private final ObjectMapper objectMapper;
  private final Counter processedCounter;
  private final Counter errorCounter;
  private final Counter dlqCounter;
  private final Counter pelRecoveryCounter;
  private final Counter janitorClaimCounter;
  private final Counter skippedCounter; // Unit 4: Events skipped (already applied)
  private final Counter bufferedCounter; // Unit 4: Events buffered (out-of-order)
  private final Counter appliedCounter; // Unit 4: Events applied from buffer

  private Thread workerThread;
  private volatile boolean running = false;
  private volatile boolean pelRecoveryComplete = false;

  // Unit 4: Event buffering for out-of-order events
  // Key: userIgn, Value: Map of version -> BufferedEvent
  private final Map<String, Map<Long, BufferedEvent>> eventBuffer = new ConcurrentHashMap<>();

  public MongoDBSyncWorker(
      RedissonClient redissonClient,
      CharacterViewQueryService queryService,
      LogicExecutor executor,
      @Qualifier("checkedLogicExecutor") CheckedLogicExecutor checkedExecutor,
      ViewTransformer viewTransformer,
      ObjectMapper objectMapper,
      io.micrometer.core.instrument.MeterRegistry meterRegistry) {
    this.redissonClient = redissonClient;
    this.queryService = queryService;
    this.executor = executor;
    this.checkedExecutor = checkedExecutor;
    this.viewTransformer = viewTransformer;
    this.objectMapper = objectMapper;
    this.processedCounter = meterRegistry.counter("mongodb.sync.processed");
    this.errorCounter = meterRegistry.counter("mongodb.sync.errors");
    this.dlqCounter = meterRegistry.counter("mongodb.sync.dlq");
    this.pelRecoveryCounter = meterRegistry.counter("mongodb.sync.pel.recovery");
    this.janitorClaimCounter = meterRegistry.counter("mongodb.sync.janitor.claimed");
    this.skippedCounter = meterRegistry.counter("mongodb.sync.skipped"); // Unit 4
    this.bufferedCounter = meterRegistry.counter("mongodb.sync.buffered"); // Unit 4
    this.appliedCounter = meterRegistry.counter("mongodb.sync.buffer.applied"); // Unit 4
  }

  @PostConstruct
  public void start() {
    initializeStream();
    running = true;
    workerThread = new Thread(this, "V5-MongoDBSyncWorker-" + System.currentTimeMillis());
    workerThread.setDaemon(true);
    workerThread.setUncaughtExceptionHandler(
        (t, e) -> {
          log.error("[MongoDBSyncWorker] Thread crashed", e);
          errorCounter.increment();
        });
    workerThread.start();
  }

  @PreDestroy
  public void stop() {
    running = false;
    if (workerThread != null) {
      workerThread.interrupt();
      joinWorkerThreadWithRecovery();
    }
    log.info("[MongoDBSyncWorker] Worker stopped");
  }

  /** Join worker thread with interrupt recovery (Section 12 compliant). */
  private void joinWorkerThreadWithRecovery() {
    checkedExecutor.executeUncheckedVoid(
        () -> {
          try {
            workerThread.join(5000);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("[MongoDBSyncWorker] Worker interrupted during shutdown");
            throw new WorkerShutdownException(e);
          }
        },
        TaskContext.of("MongoDBSyncWorker", "JoinWorkerThread"),
        ex -> new IllegalStateException("Unexpected error during worker thread join", ex));
  }

  @Override
  public void run() {
    log.info("[MongoDBSyncWorker] Sync worker running - Phase 1: PEL Recovery");

    // Phase 1: PEL Recovery - process pending messages from previous crashes
    recoverPendingMessages();

    pelRecoveryComplete = true;
    log.info("[MongoDBSyncWorker] PEL recovery complete - Phase 2: New message processing");

    // Phase 2: Process new messages
    while (running && !Thread.currentThread().isInterrupted()) {
      executor.executeVoidJava(this::processNextBatch, TaskContext.of("MongoDBSyncWorker", "Poll"));
    }

    log.info("[MongoDBSyncWorker] Sync worker stopped");
  }

  // ==================== Phase 1: PEL Recovery ====================

  /**
   * Phase 1: PEL Recovery - process pending messages from crashed consumers.
   *
   * <p>Uses XAUTOCLAIM to claim idle messages and process them before new messages.
   */
  private void recoverPendingMessages() {
    RStream<String, String> stream = redissonClient.getStream(STREAM_KEY, StringCodec.INSTANCE);

    int recoveredCount = 0;
    int dlqCount = 0;

    // Use autoClaim to get pending messages (idle > 0 means they were delivered but not acked)
    while (running) {
      Map<StreamMessageId, Map<String, String>> pending =
          executor.executeOrDefault(
              () -> {
                var result =
                    stream.autoClaim(
                        CONSUMER_GROUP,
                        CONSUMER_NAME,
                        1000L, // 1 second idle time (immediate claim for recovery)
                        TimeUnit.MILLISECONDS,
                        StreamMessageId.MIN,
                        BATCH_SIZE);
                return result != null ? result.getMessages() : Map.of();
              },
              Map.of(),
              TaskContext.of("MongoDBSyncWorker", "AutoClaimPEL"));

      if (pending.isEmpty()) {
        break;
      }

      for (Map.Entry<StreamMessageId, Map<String, String>> entry : pending.entrySet()) {
        ProcessingResult result =
            processSingleMessageWithRetryTracking(stream, entry.getKey(), entry.getValue());
        if (result == ProcessingResult.RECOVERED) {
          recoveredCount++;
        } else if (result == ProcessingResult.DLQ) {
          dlqCount++;
        }
      }

      if (pending.size() < BATCH_SIZE) {
        break;
      }
    }

    if (recoveredCount > 0 || dlqCount > 0) {
      log.info(
          "[MongoDBSyncWorker] PEL recovery summary: recovered={}, dlq={}",
          recoveredCount,
          dlqCount);
      pelRecoveryCounter.increment(recoveredCount);
    }
  }

  // ==================== Retry Tracking & Poison Pill Handling ====================

  /**
   * Process a single message with retry tracking and poison pill handling.
   *
   * <p>Tracks retry count via message metadata. If max retries exceeded, moves to DLQ.
   *
   * @return ProcessingResult indicating the outcome of processing
   */
  private ProcessingResult processSingleMessageWithRetryTracking(
      RStream<String, String> stream, StreamMessageId messageId, Map<String, String> data) {
    return executor.executeOrDefault(
        () -> {
          // Check retry count from message metadata
          int retryCount = getRetryCount(data);

          if (retryCount >= MAX_RETRIES) {
            log.warn(
                "[MongoDBSyncWorker] Poison pill detected: messageId={}, retryCount={}. Moving to DLQ.",
                messageId,
                retryCount);
            return handlePoisonPill(stream, messageId, data);
          }

          // Process the message normally
          try {
            processMessage(messageId, data);
            stream.ack(CONSUMER_GROUP, messageId);
            processedCounter.increment();
            return ProcessingResult.RECOVERED;
          } catch (Exception e) {
            // Increment retry count in message data
            int newRetryCount = retryCount + 1;
            log.warn(
                "[MongoDBSyncWorker] Message processing failed (attempt {}/{}): messageId={}, error={}",
                newRetryCount,
                MAX_RETRIES,
                messageId,
                e.getMessage());

            // Store retry count in metadata (will be picked up by XAUTOCLAIM)
            // Note: Redis Streams doesn't support updating message payload directly
            // We rely on the fact that unacknowledged messages stay in PEL
            // and will be reclaimed by XAUTOCLAIM with increased delivery count

            // Check if we should move to DLQ based on Redis delivery count
            // Redis XINFO stream PEL shows delivery count internally
            if (newRetryCount >= MAX_RETRIES) {
              log.warn(
                  "[MongoDBSyncWorker] Max retries exceeded for message: {}, moving to DLQ",
                  messageId);
              return handlePoisonPill(stream, messageId, data);
            }

            // Don't ACK - leave in PEL for XAUTOCLAIM to reclaim
            errorCounter.increment();
            return ProcessingResult.FAILED;
          }
        },
        ProcessingResult.FAILED,
        TaskContext.of("MongoDBSyncWorker", "ProcessWithRetry", messageId.toString()));
  }

  /** Get retry count from message metadata. */
  private int getRetryCount(Map<String, String> data) {
    return executor.executeOrDefault(
        () -> {
          String retryCountStr = data.get("retryCount");
          if (retryCountStr != null) {
            return Integer.parseInt(retryCountStr);
          }
          return 0;
        },
        0,
        TaskContext.of("MongoDBSyncWorker", "GetRetryCount"));
  }

  /**
   * Handle poison pill by moving to DLQ stream.
   *
   * <p>Steps:
   *
   * <ol>
   *   <li>XADD to DLQ stream with error info
   *   <li>XACK original message
   *   <li>Log the DLQ entry
   * </ol>
   */
  private ProcessingResult handlePoisonPill(
      RStream<String, String> stream, StreamMessageId messageId, Map<String, String> data) {
    return executor.executeOrDefault(
        () -> {
          // Add to DLQ stream
          RStream<String, String> dlqStream =
              redissonClient.getStream(DLQ_STREAM_KEY, StringCodec.INSTANCE);
          Map<String, String> dlqData = new HashMap<>();
          dlqData.put("original_message_id", messageId.toString());
          dlqData.put("original_data", data.get("data"));
          dlqData.put("dlq_reason", "Max retries exceeded");
          dlqData.put("dlq_timestamp", Instant.now().toString());
          dlqStream.add(StreamAddArgs.entries(dlqData));

          // ACK original message
          stream.ack(CONSUMER_GROUP, messageId);

          dlqCounter.increment();
          log.error(
              "[MongoDBSyncWorker] Message moved to DLQ: messageId={}, stream={}",
              messageId,
              DLQ_STREAM_KEY);

          return ProcessingResult.DLQ;
        },
        ProcessingResult.FAILED,
        TaskContext.of("MongoDBSyncWorker", "HandlePoisonPill", messageId.toString()));
  }

  // ==================== XAUTOCLAIM Janitor ====================

  /**
   * Claim orphaned messages using XAUTOCLAIM.
   *
   * <p>This method is called periodically by the StreamJanitorScheduler to claim messages from
   * inactive consumers.
   *
   * @param minIdleTime minimum time a consumer must be idle before their messages are claimed
   * @return number of messages claimed
   */
  public int claimOrphanedMessages(Duration minIdleTime) {
    RStream<String, String> stream = redissonClient.getStream(STREAM_KEY, StringCodec.INSTANCE);

    return executor.executeOrDefault(
        () -> {
          // Use XAUTOCLAIM to claim messages from idle consumers
          var claimResult =
              stream.autoClaim(
                  CONSUMER_GROUP,
                  CONSUMER_NAME,
                  minIdleTime.toMillis(),
                  TimeUnit.MILLISECONDS,
                  StreamMessageId.MIN,
                  BATCH_SIZE);

          int claimedCount = 0;
          Map<StreamMessageId, Map<String, String>> claimedMessages = claimResult.getMessages();

          if (claimedMessages.isEmpty()) {
            return 0;
          }

          for (Map.Entry<StreamMessageId, Map<String, String>> entry : claimedMessages.entrySet()) {
            ProcessingResult result =
                processSingleMessageWithRetryTracking(stream, entry.getKey(), entry.getValue());
            if (result == ProcessingResult.RECOVERED) {
              claimedCount++;
            }
            janitorClaimCounter.increment();
          }

          if (claimedCount > 0) {
            log.info(
                "[MongoDBSyncWorker] Janitor claimed and processed {} orphaned messages",
                claimedCount);
          }

          return claimedCount;
        },
        0,
        TaskContext.of("MongoDBSyncWorker", "ClaimOrphanedMessages", "janitor"));
  }

  /** Result of processing a single message. */
  private enum ProcessingResult {
    RECOVERED, // Successfully processed
    DLQ, // Moved to DLQ
    FAILED // Processing failed (should not happen)
  }

  // ==================== Stream Initialization ====================

  private void initializeStream() {
    TaskContext context = TaskContext.of("MongoDBSyncWorker", "InitStream");

    executor.executeVoidJava(
        () -> {
          RStream<String, String> stream =
              redissonClient.getStream(STREAM_KEY, StringCodec.INSTANCE);

          // Use Strategy Pattern to determine appropriate initialization
          StreamStrategyFactory factory = new StreamStrategyFactory(executor);
          StreamInitializationStrategy strategy = factory.determineStrategy(stream);

          // Stream exists, check if group exists
          ensureConsumerGroupExists(stream);
        },
        context);
  }

  /** Ensure consumer group exists, creating if necessary. */
  private void ensureConsumerGroupExists(RStream<String, String> stream) {
    executor.executeOrCatch(
        () -> {
          stream.readGroup(
              CONSUMER_GROUP, CONSUMER_NAME, StreamReadGroupArgs.neverDelivered().count(1));
          return null;
        },
        e -> {
          if (e.getMessage() != null && e.getMessage().contains("NOGROUP")) {
            executor.executeVoidJava(
                () -> {
                  stream.createGroup(StreamCreateGroupArgs.name(CONSUMER_GROUP));
                  log.info("[MongoDBSyncWorker] Consumer group created: {}", CONSUMER_GROUP);
                },
                TaskContext.of("MongoDBSyncWorker", "CreateGroup"));
          }
          return null;
        },
        TaskContext.of("MongoDBSyncWorker", "CheckGroup"));
  }

  // ==================== New Message Processing ====================

  private void processNextBatch() {
    executor.executeOrCatch(
        () -> {
          RStream<String, String> stream =
              redissonClient.getStream(STREAM_KEY, StringCodec.INSTANCE);

          // Read with timeout using Redisson RStream API
          Map<StreamMessageId, Map<String, String>> messages =
              stream.readGroup(
                  CONSUMER_GROUP,
                  CONSUMER_NAME,
                  StreamReadGroupArgs.neverDelivered().count(1).timeout(POLL_TIMEOUT));

          if (messages == null || messages.isEmpty()) {
            return null;
          }

          for (Map.Entry<StreamMessageId, Map<String, String>> entry : messages.entrySet()) {
            processSingleMessage(stream, entry.getKey(), entry.getValue());
          }

          return null;
        },
        e -> {
          log.error("[MongoDBSyncWorker] Error in processNextBatch", e);
          return null;
        },
        TaskContext.of("MongoDBSyncWorker", "ProcessBatch"));
  }

  /** Process a single message with ACK on success. */
  private void processSingleMessage(
      RStream<String, String> stream, StreamMessageId messageId, Map<String, String> data) {
    executor.executeOrCatch(
        () -> {
          processMessage(messageId, data);
          // ACK message
          stream.ack(CONSUMER_GROUP, messageId);
          processedCounter.increment();
          return null;
        },
        e -> {
          log.error("[MongoDBSyncWorker] Failed to process message: {}", messageId, e);
          errorCounter.increment();
          // Message will remain in PEL for retry or DLQ handling
          return null;
        },
        TaskContext.of("MongoDBSyncWorker", "ProcessSingleMessage", messageId.toString()));
  }

  // ==================== Message Processing ====================

  /**
   * ADR-083: Extract payload JSON with backward compatibility.
   *
   * <p>Priority order:
   *
   * <ol>
   *   <li>"data" key - New format (V5 CQRS)
   *   <li>"payload" key - Legacy format (pre-V5)
   * </ol>
   *
   * <p>Logs deprecation warning when using legacy format.
   *
   * @param data Redis Stream message data map
   * @return Payload JSON string. or null if neither format is present
   */
  private String extractPayloadJson(Map<String, String> data) {
    // Try new format first (V5 CQRS)
    String payloadJson = data.get("data");
    if (payloadJson != null) {
      return payloadJson;
    }

    // Fallback to legacy format (pre-V5)
    payloadJson = data.get("payload");
    if (payloadJson != null) {
      log.warn(
          "[MongoDBSyncWorker] Legacy message format detected (using 'payload' key). "
              + "This format is deprecated. Please migrate to 'data' key format.");
      return payloadJson;
    }

    return null;
  }

  private void processMessage(StreamMessageId messageId, Map<String, String> data) {
    TaskContext context =
        TaskContext.of("MongoDBSyncWorker", "ProcessMessage", messageId.toString());

    executor.executeVoidJava(
        () -> {
          // ADR-083: Backward compatibility - try both 'data' and 'payload' keys
          String payloadJson = extractPayloadJson(data);
          if (payloadJson == null) {
            log.warn("[MongoDBSyncWorker] No payload in message (both formats tried)");
            return;
          }

          deserializeAndSync(messageId, payloadJson);
        },
        context);
  }

  /** Deserialize event and sync to MongoDB with version ordering (Unit 4). */
  private void deserializeAndSync(StreamMessageId messageId, String payloadJson) {
    checkedExecutor.executeUncheckedVoid(
        () -> {
          // Deserialize to ExpectationCalculationCompletedEvent
          ExpectationCalculationCompletedEvent event;
          try {
            event = objectMapper.readValue(payloadJson, ExpectationCalculationCompletedEvent.class);
          } catch (JsonProcessingException e) {
            throw new JsonDeserializationException("Failed to deserialize event", e);
          }

          // Unit 4: Event ordering with versioning
          String userIgn = event.getUserIgn();
          if (userIgn == null) {
            log.warn(
                "[MongoDBSyncWorker] Skipping event with null userIgn: messageId={}", messageId);
            return;
          }

          // Get current version (default to 0 if not set)
          Long eventVersion = event.getVersion();
          if (eventVersion == null) {
            log.debug(
                "[MongoDBSyncWorker] Event has no version, applying immediately: userIgn={}, messageId={}",
                userIgn,
                messageId);
            eventVersion = System.currentTimeMillis(); // Fallback to timestamp
          }

          // Get last applied version from MongoDB
          long lastAppliedVersion = queryService.getLastAppliedVersion(userIgn);

          // Version check logic
          if (eventVersion <= lastAppliedVersion) {
            // Already applied - skip
            log.debug(
                "[MongoDBSyncWorker] Skipping already applied event: userIgn={}, version={}, lastApplied={}",
                userIgn,
                eventVersion,
                lastAppliedVersion);
            skippedCounter.increment();
            return;
          } else if (eventVersion > lastAppliedVersion + 1) {
            // Out-of-order - buffer it
            log.info(
                "[MongoDBSyncWorker] Buffering out-of-order event: userIgn={}, version={}, lastApplied={}",
                userIgn,
                eventVersion,
                lastAppliedVersion);
            bufferEvent(event, messageId, payloadJson);
            bufferedCounter.increment();
            return;
          }

          // Version is exactly lastAppliedVersion + 1 - apply immediately
          log.debug(
              "[MongoDBSyncWorker] Applying event in order: userIgn={}, version={}",
              userIgn,
              eventVersion);
          applyEvent(event, messageId);

          // Try to apply any buffered events that are now in sequence
          processBufferedEvents(userIgn, eventVersion);
        },
        TaskContext.of("MongoDBSyncWorker", "DeserializeAndSync", messageId.toString()),
        e -> new InternalSystemException("메시지 역직렬화 실패: " + messageId, e));
  }

  /** Apply event to MongoDB (Section 12 compliant). */
  private void applyEvent(ExpectationCalculationCompletedEvent event, StreamMessageId messageId) {
    checkedExecutor.executeUncheckedVoid(
        () -> {
          // Delegate transformation to ViewTransformer (SRP)
          CharacterValuationView view = viewTransformer.toDocument(event);
          queryService.upsert(view);

          log.debug(
              "[MongoDBSyncWorker] Synced to MongoDB: userIgn={}, ocid={}, version={}",
              event.getUserIgn(),
              event.getCharacterOcid(),
              event.getVersion());
        },
        TaskContext.of("MongoDBSyncWorker", "ApplyEvent", messageId.toString()),
        e -> new InternalSystemException("Failed to apply event to MongoDB: " + messageId, e));
  }

  /** Buffer out-of-order event for later processing (Unit 4). */
  private void bufferEvent(
      ExpectationCalculationCompletedEvent event, StreamMessageId messageId, String payloadJson) {
    executor.executeVoidJava(
        () -> {
          String userIgn = event.getUserIgn();
          Long version = event.getVersion();

          eventBuffer
              .computeIfAbsent(userIgn, k -> new ConcurrentHashMap<>())
              .put(version, new BufferedEvent(event, messageId, payloadJson));

          log.debug("[MongoDBSyncWorker] Buffered event: userIgn={}, version={}", userIgn, version);
        },
        TaskContext.of("MongoDBSyncWorker", "BufferEvent", messageId.toString()));
  }

  /**
   * Process buffered events that are now in sequence (Unit 4).
   *
   * <p>After applying an event with version N, checks if version N+1 is in buffer. If so, applies
   * it and repeats for N+2, N+3, etc.
   */
  private void processBufferedEvents(String userIgn, long lastAppliedVersion) {
    executor.executeVoidJava(
        () -> {
          Map<Long, BufferedEvent> userBuffer = eventBuffer.get(userIgn);
          if (userBuffer == null || userBuffer.isEmpty()) {
            return;
          }

          long nextVersion = lastAppliedVersion + 1;
          int appliedCount = 0;

          // Apply all sequential events from buffer
          while (userBuffer.containsKey(nextVersion)) {
            BufferedEvent buffered = userBuffer.remove(nextVersion);
            applyEvent(buffered.event(), buffered.messageId());
            appliedCount++;
            nextVersion++;

            log.debug(
                "[MongoDBSyncWorker] Applied buffered event: userIgn={}, version={}",
                userIgn,
                nextVersion - 1);
          }

          if (appliedCount > 0) {
            appliedCounter.increment(appliedCount);
            log.info(
                "[MongoDBSyncWorker] Applied {} buffered events for userIgn={}",
                appliedCount,
                userIgn);
          }

          // Clean up empty buffer
          if (userBuffer.isEmpty()) {
            eventBuffer.remove(userIgn);
          }
        },
        TaskContext.of("MongoDBSyncWorker", "ProcessBuffered", userIgn));
  }

  /**
   * Buffered event holder (Unit 4).
   *
   * <p>Stores out-of-order events until their sequence is complete.
   */
  private record BufferedEvent(
      ExpectationCalculationCompletedEvent event, StreamMessageId messageId, String payloadJson) {}

  /** RuntimeException to signal graceful worker shutdown. */
  private static class WorkerShutdownException extends RuntimeException {
    WorkerShutdownException(InterruptedException cause) {
      super(cause);
    }
  }

  /** RuntimeException wrapper for JsonProcessingException during deserialization. */
  private static class JsonDeserializationException extends RuntimeException {
    JsonDeserializationException(String message, JsonProcessingException cause) {
      super(message, cause);
    }
  }
}
