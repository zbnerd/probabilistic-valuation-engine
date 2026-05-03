package maple.expectation.application.service.expectation.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.core.dto.v4.EquipmentExpectationResponseV4;
import maple.expectation.util.GzipUtils;
import org.springframework.stereotype.Component;

/**
 * Expectation Cache Compression Service (Issue #644: God Object Decomposition)
 *
 * <p>Responsibilities:
 *
 * <ul>
 *   <li>Response → JSON → GZIP → Base64 String compression
 *   <li>Response → JSON → GZIP byte[] compression
 *   <li>Base64 → GZIP → JSON → Response decompression
 * </ul>
 *
 * <p>Extracted from ExpectationCacheCoordinator to follow Single Responsibility Principle.
 */
@Slf4j
@Component
public class ExpectationCacheCompressionService {

  private final ObjectMapper objectMapper;

  public ExpectationCacheCompressionService(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /**
   * Response → JSON → GZIP → Base64 String compression
   *
   * @param response The response to compress
   * @param userIgn Character IGN (for logging)
   * @return Base64-encoded GZIP-compressed data
   * @throws Exception If compression or serialization fails
   */
  public String compressAndSerialize(EquipmentExpectationResponseV4 response, String userIgn)
      throws Exception {
    String json = objectMapper.writeValueAsString(response);
    byte[] compressed = GzipUtils.compress(json);
    String base64 = java.util.Base64.getEncoder().encodeToString(compressed);
    log.debug(
        "[V4] GZIP+Base64 압축 완료: {} (원본: {}KB → 압축: {}KB → Base64: {}KB)",
        userIgn,
        json.length() / 1024,
        compressed.length / 1024,
        base64.length() / 1024);
    return base64;
  }

  /**
   * Response → JSON → GZIP byte[] compression (for GZIP endpoints)
   *
   * @param response The response to compress
   * @param userIgn Character IGN (for logging)
   * @return GZIP-compressed bytes
   * @throws Exception If compression or serialization fails
   */
  public byte[] compressToGzipBytes(EquipmentExpectationResponseV4 response, String userIgn)
      throws Exception {
    String json = objectMapper.writeValueAsString(response);
    byte[] compressed = GzipUtils.compress(json);
    log.debug(
        "[V4] GZIP 압축 완료: {} (원본: {}KB → 압축: {}KB)",
        userIgn,
        json.length() / 1024,
        compressed.length / 1024);
    return compressed;
  }

  /**
   * Base64 → GZIP → JSON → Response decompression
   *
   * @param compressedBase64 Base64-encoded GZIP-compressed data
   * @param userIgn Character IGN (for logging)
   * @return Decompressed response
   * @throws Exception If decompression or deserialization fails
   */
  public EquipmentExpectationResponseV4 decompress(String compressedBase64, String userIgn)
      throws Exception {
    if (compressedBase64 == null || compressedBase64.isEmpty()) {
      throw new maple.expectation.error.exception.CacheDataNotFoundException(userIgn);
    }

    byte[] compressed = java.util.Base64.getDecoder().decode(compressedBase64);
    String json = GzipUtils.decompress(compressed);
    EquipmentExpectationResponseV4 response =
        objectMapper.readValue(json, EquipmentExpectationResponseV4.class);

    log.debug(
        "[V4] GZIP 압축 해제 완료: {} (Base64: {}KB → 압축: {}KB → 원본: {}KB)",
        userIgn,
        compressedBase64.length() / 1024,
        compressed.length / 1024,
        json.length() / 1024);

    return response;
  }
}
