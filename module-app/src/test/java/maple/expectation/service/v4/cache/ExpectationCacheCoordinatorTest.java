package maple.expectation.service.v4.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.zip.GZIPOutputStream;
import maple.expectation.application.service.expectation.cache.ExpectationCacheCoordinator;
import maple.expectation.common.function.ThrowingSupplier;
import maple.expectation.error.exception.CacheDataNotFoundException;
import maple.expectation.infrastructure.cache.TieredCacheManager;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator;
import maple.expectation.web.dto.v4.EquipmentExpectationResponseV4;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.support.SimpleValueWrapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("ExpectationCacheCoordinator 단위 테스트")
class ExpectationCacheCoordinatorTest {

  private static final String TEST_USER_IGN = "testUser";

  @Mock private LogicExecutor executor;
  @Mock private TieredCacheManager tieredCacheManager;
  @Mock private Cache expectationCache;

  private MeterRegistry meterRegistry;
  private ObjectMapper objectMapper;
  private ExpectationCacheCoordinator coordinator;

  private String validCompressedBase64;
  private EquipmentExpectationResponseV4 mockResponse;

  @BeforeEach
  void setUp() throws Exception {
    meterRegistry = new SimpleMeterRegistry();
    objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    when(tieredCacheManager.getCache("expectationV4")).thenReturn(expectationCache);
    when(tieredCacheManager.getMeterRegistry()).thenReturn(meterRegistry);

    coordinator = new ExpectationCacheCoordinator(executor, objectMapper, tieredCacheManager);

    mockResponse = createMockResponse();
    String json = objectMapper.writeValueAsString(mockResponse);
    byte[] compressed = compress(json);
    validCompressedBase64 = java.util.Base64.getEncoder().encodeToString(compressed);
  }

  @Nested
  @DisplayName("getOrCalculate: 캐시 히트 시 ValueWrapper 언래핑")
  class GetOrCalculateCacheHitTests {

    @Test
    @DisplayName("캐시 히트(Base64 String) - ValueWrapper 언래핑 후 반환 성공")
    void cacheHitWithBase64String_ShouldUnwrapValueWrapperAndReturn() {
      Cache.ValueWrapper wrapper = new SimpleValueWrapper(validCompressedBase64);
      when(expectationCache.get(TEST_USER_IGN)).thenReturn(wrapper);

      // Mock decompression (called when cache hits)
      when(executor.executeWithTranslation(
              any(ThrowingSupplier.class), any(ExceptionTranslator.class), any(TaskContext.class)))
          .thenReturn(mockResponse);

      Callable<EquipmentExpectationResponseV4> calculator = () -> mockResponse;
      EquipmentExpectationResponseV4 result =
          coordinator.getOrCalculate(TEST_USER_IGN, false, calculator);

      assertThat(result).isNotNull();
      assertThat(result.getUserIgn()).isEqualTo(TEST_USER_IGN);
      assertThat(result.isFromCache()).isTrue();
      verify(expectationCache).get(TEST_USER_IGN);
    }

    @Test
    @DisplayName("캐시 미스 - calculator 실행 후 저장")
    void cacheMiss_ShouldExecuteCalculatorAndStore() {
      when(expectationCache.get(TEST_USER_IGN)).thenReturn(null);

      when(executor.execute(any(ThrowingSupplier.class), any(TaskContext.class)))
          .thenReturn(mockResponse);

      when(executor.executeWithTranslation(
              any(ThrowingSupplier.class), any(ExceptionTranslator.class), any(TaskContext.class)))
          .thenReturn(validCompressedBase64);

      Callable<EquipmentExpectationResponseV4> calculator = () -> mockResponse;
      EquipmentExpectationResponseV4 result =
          coordinator.getOrCalculate(TEST_USER_IGN, false, calculator);

      assertThat(result).isNotNull();
      assertThat(result.getUserIgn()).isEqualTo(TEST_USER_IGN);
      verify(expectationCache).put(eq(TEST_USER_IGN), eq(validCompressedBase64));
    }

    @Test
    @DisplayName("캐시 히트 - 빈 ValueWrapper(null inside) - Cache MISS로 처리")
    void cacheHitWithNullValue_ShouldTreatAsMiss() {
      Cache.ValueWrapper wrapper = new SimpleValueWrapper(null);
      when(expectationCache.get(TEST_USER_IGN)).thenReturn(wrapper);

      when(executor.execute(any(ThrowingSupplier.class), any(TaskContext.class)))
          .thenReturn(mockResponse);

      when(executor.executeWithTranslation(
              any(ThrowingSupplier.class), any(ExceptionTranslator.class), any(TaskContext.class)))
          .thenReturn(validCompressedBase64);

      Callable<EquipmentExpectationResponseV4> calculator = () -> mockResponse;
      EquipmentExpectationResponseV4 result =
          coordinator.getOrCalculate(TEST_USER_IGN, false, calculator);

      assertThat(result).isNotNull();
      assertThat(result.getUserIgn()).isEqualTo(TEST_USER_IGN);
      verify(expectationCache).put(eq(TEST_USER_IGN), eq(validCompressedBase64));
    }
  }

  @Nested
  @DisplayName("getGzipOrCalculate: 캐시 히트 시 ValueWrapper 언래핑")
  class GetGzipOrCalculateCacheHitTests {

    @Test
    @DisplayName("캐시 히트(Base64 String) - ValueWrapper 언래핑 후 GZIP 바이트 반환")
    void cacheHitWithBase64String_ShouldReturnGzipBytes() {
      Cache.ValueWrapper wrapper = new SimpleValueWrapper(validCompressedBase64);
      when(expectationCache.get(TEST_USER_IGN)).thenReturn(wrapper);

      Callable<EquipmentExpectationResponseV4> calculator = () -> mockResponse;
      byte[] result = coordinator.getGzipOrCalculate(TEST_USER_IGN, false, calculator);

      assertThat(result).isNotNull();
      assertThat(result).isNotEmpty();
      verify(expectationCache).get(TEST_USER_IGN);
    }

    @Test
    @DisplayName("캐시 미스 - calculator 실행 후 GZIP 바이트 반환")
    void cacheMiss_ShouldExecuteCalculatorAndReturnGzipBytes() {
      when(expectationCache.get(TEST_USER_IGN)).thenReturn(null);

      when(executor.execute(any(ThrowingSupplier.class), any(TaskContext.class)))
          .thenReturn(mockResponse);

      when(executor.executeWithTranslation(
              any(ThrowingSupplier.class), any(ExceptionTranslator.class), any(TaskContext.class)))
          .thenReturn(validCompressedBase64);

      Callable<EquipmentExpectationResponseV4> calculator = () -> mockResponse;
      byte[] result = coordinator.getGzipOrCalculate(TEST_USER_IGN, false, calculator);

      assertThat(result).isNotNull();
      assertThat(result).isNotEmpty();
      verify(expectationCache).put(eq(TEST_USER_IGN), eq(validCompressedBase64));
    }

    @Test
    @DisplayName("캐시 히트 - 빈 문자열 - CacheDataNotFoundException 발생")
    void cacheHitWithEmptyString_ShouldThrowCacheDataNotFoundException() {
      Cache.ValueWrapper wrapper = new SimpleValueWrapper("");
      when(expectationCache.get(TEST_USER_IGN)).thenReturn(wrapper);

      Callable<EquipmentExpectationResponseV4> calculator = () -> mockResponse;

      assertThatThrownBy(() -> coordinator.getGzipOrCalculate(TEST_USER_IGN, false, calculator))
          .isInstanceOf(CacheDataNotFoundException.class);
    }
  }

  @Nested
  @DisplayName("force refresh - 캐시 무시 및 갱신")
  class ForceRefreshTests {

    @Test
    @DisplayName("force=true - 캐시 무시하고 calculator 실행 후 갱신")
    void forceRefresh_ShouldIgnoreCacheAndUpdate() {
      when(executor.execute(any(ThrowingSupplier.class), any(TaskContext.class)))
          .thenReturn(mockResponse);

      when(executor.executeWithTranslation(
              any(ThrowingSupplier.class), any(ExceptionTranslator.class), any(TaskContext.class)))
          .thenReturn(validCompressedBase64);

      Callable<EquipmentExpectationResponseV4> calculator = () -> mockResponse;
      EquipmentExpectationResponseV4 result =
          coordinator.getOrCalculate(TEST_USER_IGN, true, calculator);

      assertThat(result).isNotNull();
      assertThat(result.getUserIgn()).isEqualTo(TEST_USER_IGN);
      verify(executor).execute(any(ThrowingSupplier.class), any(TaskContext.class));
      verify(expectationCache).put(eq(TEST_USER_IGN), eq(validCompressedBase64));
      verify(expectationCache, org.mockito.Mockito.never()).get(TEST_USER_IGN);
    }
  }

  private EquipmentExpectationResponseV4 createMockResponse() {
    return EquipmentExpectationResponseV4.builder()
        .userIgn(TEST_USER_IGN)
        .calculatedAt(java.time.LocalDateTime.now())
        .fromCache(true)
        .totalExpectedCost(new BigDecimal("100000"))
        .totalCostText("100,000 메소")
        .totalCostBreakdown(EquipmentExpectationResponseV4.CostBreakdownDto.empty())
        .maxPresetNo(1)
        .presets(java.util.List.of())
        .build();
  }

  private byte[] compress(String data) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length());
    try (GZIPOutputStream gzip = new GZIPOutputStream(bos)) {
      gzip.write(data.getBytes(StandardCharsets.UTF_8));
    }
    return bos.toByteArray();
  }
}
