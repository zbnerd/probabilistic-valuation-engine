package maple.expectation.application.service.expectation.cache;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.Callable;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.common.executor.TaskContext;
import maple.expectation.core.dto.v4.EquipmentExpectationResponseV4;
import maple.expectation.core.port.inbound.CacheManagerPort;
import maple.expectation.core.port.inbound.ExecutorPort;
import maple.expectation.error.exception.CacheDataNotFoundException;
import maple.expectation.error.exception.EquipmentDataProcessingException;
import maple.expectation.infrastructure.admission.AdmissionRejectedException;
import maple.expectation.infrastructure.admission.AdmissionTimeoutException;
import maple.expectation.infrastructure.admission.GlobalAdmissionControl;
import org.springframework.cache.Cache;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * 기대값 캐시 코디네이터 (Issue #644: God Object Decomposition)
 *
 * <h3>책임 (SRP 준수)</h3>
 *
 * <ul>
 *   <li>Singleflight 패턴: 캐시 조회/저장 오케스트레이션
 *   <li>L1 Fast Path 직접 조회
 *   <li>Admission Control 연계
 * </ul>
 *
 * <h3>위임한 책임</h3>
 *
 * <ul>
 *   <li>압축/해제: {@link ExpectationCacheCompressionService}
 *   <li>포맷 변환: {@link CacheValueConverter}
 *   <li>응답 빌딩: {@link CachedResponseBuilder}
 * </ul>
 */
@Slf4j
@Component
public class ExpectationCacheCoordinator {

  private static final String CACHE_NAME = "expectationV4";

  private final ExecutorPort executorPort;
  private final Cache expectationCache;
  private final CacheManagerPort cacheManagerPort;
  private final MeterRegistry meterRegistry;
  private final GlobalAdmissionControl admissionControl;
  private final ExpectationCacheCompressionService compressionService;
  private final CacheValueConverter valueConverter;
  private final CachedResponseBuilder responseBuilder;

  /** Constructor with admission control (US-002: Issue #617) */
  @org.springframework.beans.factory.annotation.Autowired
  public ExpectationCacheCoordinator(
      ExecutorPort executorPort,
      CacheManagerPort cacheManagerPort,
      GlobalAdmissionControl admissionControl,
      ExpectationCacheCompressionService compressionService,
      CacheValueConverter valueConverter,
      CachedResponseBuilder responseBuilder) {
    this.executorPort = executorPort;
    this.cacheManagerPort = cacheManagerPort;
    this.expectationCache = (Cache) cacheManagerPort.getCache(CACHE_NAME);
    this.meterRegistry = (MeterRegistry) cacheManagerPort.getMeterRegistry();
    this.admissionControl = admissionControl;
    this.compressionService = compressionService;
    this.valueConverter = valueConverter;
    this.responseBuilder = responseBuilder;
  }

  /**
   * Constructor without admission control (backward compatibility)
   *
   * @deprecated Use {@link #ExpectationCacheCoordinator(ExecutorPort, CacheManagerPort,
   *     GlobalAdmissionControl, ExpectationCacheCompressionService, CacheValueConverter,
   *     CachedResponseBuilder)} instead. This constructor will be removed in v2.0.0. Please provide
   *     admission control explicitly.
   */
  @Deprecated
  public ExpectationCacheCoordinator(
      ExecutorPort executorPort,
      CacheManagerPort cacheManagerPort,
      ExpectationCacheCompressionService compressionService,
      CacheValueConverter valueConverter,
      CachedResponseBuilder responseBuilder) {
    this(executorPort, cacheManagerPort, null, compressionService, valueConverter, responseBuilder);
  }

  /**
   * Singleflight 패턴으로 기대값 조회 또는 계산 (#262)
   *
   * <h3>핵심 원칙 (#262 Fix)</h3>
   *
   * <ul>
   *   <li>캐시 히트: 압축 해제 후 반환 (계산 절대 금지)
   *   <li>캐시 미스: Callable 내에서만 계산
   *   <li>압축 해제 실패: 예외 발생 (재계산 X)
   * </ul>
   *
   * @param userIgn 캐릭터 IGN
   * @param force true: 캐시 무시, false: Singleflight 캐시 사용
   * @param calculator 캐시 미스 시 실행될 계산 로직
   * @param presetNo 프리셋 번호 (1-3)
   * @return 기대값 응답
   */
  public EquipmentExpectationResponseV4 getOrCalculate(
      String userIgn, boolean force, Callable<EquipmentExpectationResponseV4> calculator) {
    return getOrCalculate(userIgn, force, calculator, 1);
  }

  public EquipmentExpectationResponseV4 getOrCalculate(
      String userIgn,
      boolean force,
      Callable<EquipmentExpectationResponseV4> calculator,
      int presetNo) {
    String cacheKey = buildCacheKey(userIgn, presetNo);

    if (force) {
      log.info("[V4] Force refresh - 캐시 무시 및 갱신: {}", cacheKey);
      EquipmentExpectationResponseV4 response = executeCalculator(calculator);
      String compressedBase64 =
          executorPort.executeWithTranslation(
              () -> {
                try {
                  return compressionService.compressAndSerialize(response, cacheKey);
                } catch (Exception ex) {
                  throw new RuntimeException(ex);
                }
              },
              (e, ctx) ->
                  new EquipmentDataProcessingException(
                      String.format(
                          "Cache serialization failed [%s]: %s", ctx.toTaskName(), cacheKey),
                      e),
              TaskContext.of("CacheCoordinator", "SerializeForce", cacheKey));
      expectationCache.put(cacheKey, compressedBase64);
      return response;
    }

    Cache.ValueWrapper wrapper = expectationCache.get(cacheKey);
    if (wrapper != null) {
      Object cachedValue = valueConverter.extractValue(wrapper);
      String compressedBase64 =
          valueConverter.convertToBase64(cachedValue, cacheKey, expectationCache);
      if (compressedBase64 != null) {
        return decompressCachedResponse(compressedBase64, cacheKey);
      }
    }

    // Cache miss - calculate and store
    log.info("[V4] Cache MISS - 계산 시작: {}", cacheKey);
    EquipmentExpectationResponseV4 response = executeCalculatorWithAdmission(cacheKey, calculator);
    String compressedBase64 =
        executorPort.executeWithTranslation(
            () -> {
              try {
                return compressionService.compressAndSerialize(response, cacheKey);
              } catch (Exception ex) {
                throw new RuntimeException(ex);
              }
            },
            (e, ctx) ->
                new EquipmentDataProcessingException(
                    String.format(
                        "Cache serialization failed [%s]: %s", ctx.toTaskName(), cacheKey),
                    e),
            TaskContext.of("CacheCoordinator", "Serialize", cacheKey));
    expectationCache.put(cacheKey, compressedBase64);

    return response;
  }

  /**
   * GZIP 압축된 기대값 응답 반환 (#262 성능 최적화)
   *
   * @param userIgn 캐릭터 IGN
   * @param force true: 캐시 무시, false: 캐시 사용
   * @param calculator 캐시 미스 시 실행될 계산 로직
   * @param presetNo 프리셋 번호 (1-3)
   * @return GZIP 압축된 바이트 배열
   */
  public byte[] getGzipOrCalculate(
      String userIgn, boolean force, Callable<EquipmentExpectationResponseV4> calculator) {
    return getGzipOrCalculate(userIgn, force, calculator, 1);
  }

  public byte[] getGzipOrCalculate(
      String userIgn,
      boolean force,
      Callable<EquipmentExpectationResponseV4> calculator,
      int presetNo) {
    String cacheKey = buildCacheKey(userIgn, presetNo);

    if (force) {
      log.info("[V4] Force refresh (GZIP) - 캐시 무시 및 갱신: {}", cacheKey);
      EquipmentExpectationResponseV4 response = executeCalculator(calculator);
      String compressedBase64 =
          executorPort.executeWithTranslation(
              () -> {
                try {
                  return compressionService.compressAndSerialize(response, cacheKey);
                } catch (Exception ex) {
                  throw new RuntimeException(ex);
                }
              },
              (e, ctx) ->
                  new EquipmentDataProcessingException(
                      String.format(
                          "Cache serialization failed [%s]: %s", ctx.toTaskName(), cacheKey),
                      e),
              TaskContext.of("CacheCoordinator", "SerializeGzipForce", cacheKey));
      expectationCache.put(cacheKey, compressedBase64);
      return java.util.Base64.getDecoder().decode(compressedBase64);
    }

    Cache.ValueWrapper wrapper = expectationCache.get(cacheKey);
    if (wrapper != null) {
      Object cachedValue = valueConverter.extractValue(wrapper);
      String compressedBase64 =
          valueConverter.convertToBase64(cachedValue, cacheKey, expectationCache);
      if (compressedBase64 == null || compressedBase64.isEmpty()) {
        throw new CacheDataNotFoundException(cacheKey);
      }
      log.debug("[V4] GZIP Cache HIT: {} ({}KB)", cacheKey, compressedBase64.length() / 1024);
      return java.util.Base64.getDecoder().decode(compressedBase64);
    }

    // Cache miss - calculate and store
    log.info("[V4] Cache MISS (GZIP) - 계산 시작: {}", cacheKey);
    EquipmentExpectationResponseV4 response = executeCalculatorWithAdmission(cacheKey, calculator);
    String compressedBase64 =
        executorPort.executeWithTranslation(
            () -> {
              try {
                return compressionService.compressAndSerialize(response, cacheKey);
              } catch (Exception ex) {
                throw new RuntimeException(ex);
              }
            },
            (e, ctx) ->
                new EquipmentDataProcessingException(
                    String.format(
                        "Cache serialization failed [%s]: %s", ctx.toTaskName(), cacheKey),
                    e),
            TaskContext.of("CacheCoordinator", "SerializeGzip", cacheKey));
    expectationCache.put(cacheKey, compressedBase64);

    return java.util.Base64.getDecoder().decode(compressedBase64);
  }

  /**
   * L1 캐시 직접 조회 - Fast Path (#264 성능 최적화)
   *
   * @param userIgn 캐릭터 IGN
   * @return GZIP 바이트 (L1 히트 시), null (L1 미스 시)
   */
  @Nullable public byte[] getGzipFromL1CacheDirect(String userIgn) {
    Cache l1Cache = (Cache) cacheManagerPort.getL1CacheDirect(CACHE_NAME);
    if (l1Cache == null) {
      recordFastPathMiss();
      return null;
    }

    Cache.ValueWrapper wrapper = l1Cache.get(userIgn);
    if (wrapper == null || wrapper.get() == null) {
      recordFastPathMiss();
      return null;
    }

    Object cachedValue = wrapper.get();
    byte[] gzipBytes = valueConverter.convertToGzipBytes(cachedValue, userIgn);

    if (gzipBytes == null) {
      recordFastPathMiss();
      return null;
    }

    recordFastPathHit();
    log.debug("[V4] L1 Fast Path HIT: {} ({}KB)", userIgn, gzipBytes.length / 1024);
    return gzipBytes;
  }

  // ==================== Internal Methods ====================

  /**
   * Build cache key based on presetNo. presetNo=1 uses backward-compatible key (just userIgn).
   * presetNo=2,3 use userIgn:presetNo format.
   */
  private String buildCacheKey(String userIgn, int presetNo) {
    return (presetNo == 1) ? userIgn : userIgn + ":p" + presetNo;
  }

  private EquipmentExpectationResponseV4 executeCalculator(
      Callable<EquipmentExpectationResponseV4> calculator) {
    return executorPort.execute(
        () -> {
          try {
            return calculator.call();
          } catch (Exception e) {
            if (e instanceof RuntimeException) {
              throw (RuntimeException) e;
            }
            throw new RuntimeException(e);
          }
        },
        TaskContext.of("CacheCoordinator", "Calculate"));
  }

  /**
   * Execute calculator with global admission control (US-002: Issue #617)
   *
   * <h3>Admission Control Integration</h3>
   *
   * <ul>
   *   <li>If admissionControl is available: wrap with submitOrWait() for global concurrency limit
   *   <li>If admissionControl is null: execute directly (backward compatibility)
   *   <li>Preserves single-key single-flight behavior (handled by caller via cache check)
   * </ul>
   *
   * <p>submitOrWait()가 반환하는 CF를 체이닝으로 처리합니다. 이 메서드는 동기 API (getOrCalculate)에서 호출되므로 CF 결과를 동기적으로
   * 반환해야 합니다.
   *
   * @param cacheKey Request key for admission control (includes presetNo if applicable)
   * @param calculator Cold-path calculation task
   * @return Calculation result
   */
  private EquipmentExpectationResponseV4 executeCalculatorWithAdmission(
      String cacheKey, Callable<EquipmentExpectationResponseV4> calculator) {
    if (admissionControl == null) {
      log.debug("[V4] Admission control disabled - executing directly: {}", cacheKey);
      return executeCalculator(calculator);
    }

    log.debug("[V4] Admission control enabled - queuing calculation: {}", cacheKey);
    return admissionControl
        .submitOrWait(cacheKey, calculator)
        .exceptionally(exception -> handleAdmissionException(cacheKey, exception))
        .join(); // Sync boundary: getOrCalculate is a synchronous API (Callable-based)
  }

  /** Admission control 예외 처리 (CF exceptionally 핸들러, lambda 추출) */
  private EquipmentExpectationResponseV4 handleAdmissionException(
      String cacheKey, Throwable exception) {
    Throwable cause = exception.getCause();
    if (cause instanceof AdmissionTimeoutException) {
      log.error("[V4] Admission control timeout for: {}", cacheKey);
      throw new EquipmentDataProcessingException(
          String.format("Calculation rejected due to system overload: %s", cacheKey), cause);
    }
    if (cause instanceof AdmissionRejectedException) {
      log.warn("[V4] Admission control queue full - rejecting: {}", cacheKey);
      throw new EquipmentDataProcessingException(
          String.format("System at capacity - queue full: %s", cacheKey), cause);
    }
    log.error("[V4] Unexpected exception during admission control for: {}", cacheKey, cause);
    throw new EquipmentDataProcessingException(
        String.format("Calculation failed with admission control: %s", cacheKey), cause);
  }

  /** Base64 → GZIP byte[] → JSON → Response 압축 해제 (#262 Fix) */
  private EquipmentExpectationResponseV4 decompressCachedResponse(
      String compressedBase64, String cacheKey) {
    try {
      return executorPort.executeWithTranslation(
          () -> {
            try {
              EquipmentExpectationResponseV4 response =
                  compressionService.decompress(compressedBase64, cacheKey);
              return responseBuilder.buildWithCacheFlag(response);
            } catch (Exception ex) {
              throw new RuntimeException(ex);
            }
          },
          (e, context) ->
              new EquipmentDataProcessingException(
                  String.format("GZIP 압축 해제 실패 [%s]: %s", context.toTaskName(), cacheKey), e),
          TaskContext.of("CacheCoordinator", "Decompress", cacheKey));
    } catch (EquipmentDataProcessingException e) {
      // Cache defense: evict corrupt data on decompress failure
      log.warn("[V4] Evicting corrupt cache entry after decompress failure: {}", cacheKey);
      expectationCache.evict(cacheKey);
      throw e;
    }
  }

  // ==================== Metrics ====================

  private void recordFastPathHit() {
    meterRegistry.counter("cache.l1.fast_path", "result", "hit").increment();
  }

  private void recordFastPathMiss() {
    meterRegistry.counter("cache.l1.fast_path", "result", "miss").increment();
  }
}
