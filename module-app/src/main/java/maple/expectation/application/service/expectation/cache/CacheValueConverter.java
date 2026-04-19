package maple.expectation.application.service.expectation.cache;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.error.exception.EquipmentDataProcessingException;
import org.springframework.cache.Cache;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Cache Value Converter (Issue #644: God Object Decomposition)
 *
 * <p>Responsibilities:
 *
 * <ul>
 *   <li>Legacy byte[] → Base64 String migration
 *   <li>Spring SimpleValueWrapper unwrapping
 *   <li>L1 cache value format conversion
 * </ul>
 *
 * <p>Extracted from ExpectationCacheCoordinator to follow Single Responsibility Principle.
 */
@Slf4j
@Component
public class CacheValueConverter {

  /**
   * Convert cached value to Base64 String with migration support
   *
   * <p>Handles: SimpleValueWrapper unwrapping, byte[] → String migration
   *
   * @param cachedValue Cache value (may be wrapped, byte[], or String)
   * @param userIgn Character IGN (for logging)
   * @param expectationCache Cache instance for migration updates
   * @return Base64 String or null if value is null
   */
  @Nullable public String convertToBase64(Object cachedValue, String userIgn, Cache expectationCache) {
    // Unwrap SimpleValueWrapper (Spring Cache wrapper)
    Object unwrappedValue = unwrapValueWrapper(cachedValue);
    if (unwrappedValue == null) {
      log.warn("[V4] Cache value is null (treat as MISS): {}", userIgn);
      return null;
    }

    if (unwrappedValue instanceof String base64) {
      log.debug("[V4] Cache HIT (New Base64 format): {}", userIgn);
      return base64;
    }

    if (unwrappedValue instanceof byte[] oldGzipBytes) {
      log.warn(
          "[V4] Legacy byte[] format detected - migrating to Base64: {} ({}KB)",
          userIgn,
          oldGzipBytes.length / 1024);
      String migratedBase64 = java.util.Base64.getEncoder().encodeToString(oldGzipBytes);
      // Migrate to new format
      expectationCache.put(userIgn, migratedBase64);
      log.info("[V4] Migration complete: {}", userIgn);
      return migratedBase64;
    }

    log.error(
        "[V4] Unknown cache value type: {} (unwrapped: {}) for userIgn={}",
        cachedValue.getClass(),
        unwrappedValue.getClass(),
        userIgn);
    throw new EquipmentDataProcessingException(
        String.format("Invalid cache value type: %s", cachedValue.getClass()));
  }

  /**
   * Convert cached value to GZIP bytes (L1 Fast Path)
   *
   * @param cachedValue Cache value (byte[] or String)
   * @param userIgn Character IGN (for logging)
   * @return GZIP bytes or null if conversion fails
   */
  @Nullable public byte[] convertToGzipBytes(Object cachedValue, String userIgn) {
    if (cachedValue instanceof String base64) {
      return java.util.Base64.getDecoder().decode(base64);
    }

    if (cachedValue instanceof byte[] gzipBytes) {
      log.warn(
          "[V4] L1 Legacy byte[] format detected: {} ({}KB)", userIgn, gzipBytes.length / 1024);
      return gzipBytes;
    }

    log.error(
        "[V4] L1 Unknown cache value type: {} for userIgn={}", cachedValue.getClass(), userIgn);
    return null;
  }

  /**
   * Unwrap Spring's SimpleValueWrapper if present
   *
   * @param cachedValue Potentially wrapped cache value
   * @return Unwrapped value or original value if not wrapped
   */
  @Nullable private Object unwrapValueWrapper(Object cachedValue) {
    Object unwrappedValue = cachedValue;
    if (cachedValue instanceof org.springframework.cache.support.SimpleValueWrapper wrapper) {
      unwrappedValue = wrapper.get();
      log.debug("[V4] Unwrapped SimpleValueWrapper");
    }
    return unwrappedValue;
  }

  /**
   * Extract raw value from Cache.ValueWrapper
   *
   * @param wrapper Spring Cache ValueWrapper
   * @return Wrapped value or null if wrapper is null
   */
  @Nullable public Object extractValue(Cache.ValueWrapper wrapper) {
    return wrapper != null ? wrapper.get() : null;
  }
}
