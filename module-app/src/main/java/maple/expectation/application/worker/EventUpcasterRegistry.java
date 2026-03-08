package maple.expectation.application.worker;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

/**
 * Registry for event upcasters supporting schema evolution.
 *
 * <p>Manages registration and retrieval of upcaster chains for different event types. Each upcaster
 * chain transforms events from one schema version to the next.
 *
 * <h3>Thread Safety</h3>
 *
 * Uses ConcurrentHashMap for thread-safe upcaster registration and lookup.
 *
 * @see EventUpcaster
 */
@Component
public class EventUpcasterRegistry {

  /** Map: eventType -> List of upcaster chains */
  private final Map<String, List<EventUpcaster.UpcasterChain>> registry = new ConcurrentHashMap<>();

  /**
   * Register an upcaster for a specific event type and version transition.
   *
   * @param eventType Event type identifier (e.g., "CharacterCreated")
   * @param sourceVersion Source schema version
   * @param targetVersion Target schema version
   * @param upcaster Upcaster function to apply
   */
  public void register(
      String eventType,
      int sourceVersion,
      int targetVersion,
      EventUpcaster.UpcasterFunction upcaster) {
    EventUpcaster.UpcasterChain chain =
        new EventUpcaster.UpcasterChain(sourceVersion, targetVersion, upcaster);

    // CopyOnWriteArrayList maintains insertion order, no sort needed
    registry.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(chain);
  }

  /**
   * Get all registered upcaster chains for an event type.
   *
   * @param eventType Event type identifier
   * @return List of upcaster chains (empty list if none registered)
   */
  public List<EventUpcaster.UpcasterChain> getChains(String eventType) {
    return registry.getOrDefault(eventType, List.of());
  }

  /**
   * Check if any upcasters are registered for an event type.
   *
   * @param eventType Event type identifier
   * @return true if upcasters exist, false otherwise
   */
  public boolean hasUpcasters(String eventType) {
    return registry.containsKey(eventType) && !registry.get(eventType).isEmpty();
  }

  /** Clear all registered upcasters (primarily for testing). */
  public void clear() {
    registry.clear();
  }

  /**
   * Get the number of event types with registered upcasters.
   *
   * @return Count of event types
   */
  public int size() {
    return registry.size();
  }
}
