package maple.expectation.application.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Event Upcaster for Schema Evolution
 *
 * <h3>Purpose</h3>
 *
 * Enables smooth event schema evolution without breaking existing consumers. Upcasters transform
 * older event versions to the current schema before processing.
 *
 * <h3>Pattern</h3>
 *
 * - Chain of Responsibility: Each upcaster handles a specific version transition - Version
 * Detection: Events are tagged with their schema version - Incremental Transformation: V1 -> V2 ->
 * V3 -> ... -> Current
 *
 * <h3>Usage</h3>
 *
 * <pre>
 * // Register upcasters
 * upcasterRegistry.register("CharacterCreated", 1, 2, this::upcastCharacterCreatedV1toV2);
 *
 * // Apply upcasting
 * String currentPayload = eventUpcaster.upcast(eventType, version, payload);
 * </pre>
 *
 * @see EventUpcasterRegistry
 */
@Component
public class EventUpcaster {

  private static final Logger log = LoggerFactory.getLogger(EventUpcaster.class);

  private final EventUpcasterRegistry registry;
  private final ObjectMapper objectMapper;
  private final LogicExecutor executor;

  public EventUpcaster(
      EventUpcasterRegistry registry, ObjectMapper objectMapper, LogicExecutor executor) {
    this.registry = registry;
    this.objectMapper = objectMapper;
    this.executor = executor;
  }

  /**
   * Upcast event payload to current version
   *
   * <p>Applies registered upcasters sequentially to transform the payload from its source version
   * to the current version.
   *
   * @param eventType Event type identifier
   * @param sourceVersion Source schema version
   * @param payload Original event payload (JSON string)
   * @return Upcasted payload (JSON string)
   * @throws EventUpcastingException if upcasting fails
   */
  public String upcast(String eventType, int sourceVersion, String payload) {
    var context = TaskContext.of("EventUpcaster", "Upcast", eventType);

    return executor.executeOrDefault(
        () -> doUpcast(eventType, sourceVersion, payload),
        payload, // Return original if upcasting fails (fail-open for resilience)
        context);
  }

  /** Perform upcasting through the registered chain */
  private String doUpcast(String eventType, int sourceVersion, String payload) {
    int currentVersion = sourceVersion;
    String currentPayload = payload;

    // Find all upcasters for this event type
    List<UpcasterChain> chains = registry.getChains(eventType);

    if (chains.isEmpty()) {
      log.debug(
          "[EventUpcaster] No upcasters registered for eventType={}, version={}",
          eventType,
          sourceVersion);
      return currentPayload;
    }

    // Apply upcasters in sequence
    for (UpcasterChain chain : chains) {
      if (currentVersion >= chain.targetVersion()) {
        // Already at or past this version
        continue;
      }

      log.info(
          "[EventUpcaster] Upcasting: eventType={} from V{} to V{}",
          eventType,
          currentVersion,
          chain.targetVersion());

      try {
        JsonNode payloadNode = objectMapper.readTree(currentPayload);
        JsonNode upcastedNode = chain.upcaster().upcast(payloadNode);
        currentPayload = objectMapper.writeValueAsString(upcastedNode);
        currentVersion = chain.targetVersion();

        log.debug(
            "[EventUpcaster] Successfully upcasted to V{}: eventType={}",
            currentVersion,
            eventType);
      } catch (Exception e) {
        log.error(
            "[EventUpcaster] Failed to upcast eventType={} from V{} to V{}",
            eventType,
            currentVersion,
            chain.targetVersion(),
            e);
        throw new EventUpcastingException(
            String.format(
                "Failed to upcast event %s from V%d to V%d",
                eventType, currentVersion, chain.targetVersion()),
            e);
      }
    }

    log.info(
        "[EventUpcaster] Upcast complete: eventType={} V{} -> V{}",
        eventType,
        sourceVersion,
        currentVersion);

    return currentPayload;
  }

  /** Check if an event needs upcasting */
  public boolean needsUpcasting(String eventType, int sourceVersion) {
    List<UpcasterChain> chains = registry.getChains(eventType);
    return chains.stream().anyMatch(chain -> sourceVersion < chain.targetVersion());
  }

  /** Functional interface for upcaster implementations */
  @FunctionalInterface
  public interface UpcasterFunction {
    JsonNode upcast(JsonNode sourcePayload) throws Exception;
  }

  /** Record representing an upcaster in the chain */
  public record UpcasterChain(int sourceVersion, int targetVersion, UpcasterFunction upcaster) {
    public UpcasterChain {
      if (targetVersion <= sourceVersion) {
        throw new IllegalArgumentException("Target version must be greater than source version");
      }
    }
  }
}

/** Exception thrown when event upcasting fails */
class EventUpcastingException extends RuntimeException {
  public EventUpcastingException(String message) {
    super(message);
  }

  public EventUpcastingException(String message, Throwable cause) {
    super(message, cause);
  }
}
