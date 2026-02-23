package maple.expectation.service.v4.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.Callable;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4;
import maple.expectation.error.exception.CacheDataNotFoundException;
import maple.expectation.infrastructure.cache.TieredCacheManager;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.support.SimpleValueWrapper;

/**
 * ExpectationCacheCoordinator 단위 테스트
 *
 * <p>ADR-083: Cache.ValueWrapper 명시적 언래핑 검증
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExpectationCacheCoordinator 단위 테스트")
class ExpectationCacheCoordinatorTest {

  private static final String TEST_USER_IGN = "testUser";
  private static final String TEST_COMPRESSED_BASE64 = "H4sIAAAAAAAA"; // GZIP + Base64

  @Mock private LogicExecutor executor;
  @Mock private TieredCacheManager tieredCacheManager;
  @Mock private Cache expectationCache;

  private MeterRegistry meterRegistry;
  private ObjectMapper objectMapper;
  private ExpectationCacheCoordinator coordinator;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    objectMapper = new ObjectMapper();

    when(tieredCacheManager.getCache("expectationV4")).thenReturn(expectationCache);
    when(tieredCacheManager.getMeterRegistry()).thenReturn(meterRegistry);

    coordinator = new ExpectationCacheCoordinator(executor, objectMapper, tieredCacheManager);
  }

  @Nested
  @DisplayName("getOrCalculate: 캐시 히트 시 ValueWrapper 언래핑")
  class GetOrCalculateCacheHitTests {

    @Test
    @DisplayName("캐시 히트(Base64 String) - ValueWrapper 언래핑 후 반환 성공")
    void cacheHitWithBase64String_ShouldUnwrapValueWrapperAndReturn() throws Exception {
      // Given: 캐시에 Base64 String이 ValueWrapper로 감싸져 저장됨
      EquipmentExpectationResponseV4 mockResponse = createMockResponse();
      Cache.ValueWrapper wrapper = new SimpleValueWrapper(TEST_COMPRESSED_BASE64);
      when(expectationCache.get(TEST_USER_IGN)).thenReturn(wrapper);

      when(executor.execute(any(), any(TaskContext.class)))
          .thenAnswer(
              invocation -> {
                Callable<EquipmentExpectationResponseV4> task = invocation.getArgument(0);
                return decompressAndDeserialize(task.call());
              });

      // When: 캐시 조회
      Callable<EquipmentExpectationResponseV4> calculator = () -> mockResponse;
      EquipmentExpectationResponseV4 result =
          coordinator.getOrCalculate(TEST_USER_IGN, false, calculator);

      // Then: ValueWrapper 언래핑 후 압축 해제 성공, calculator 미실행
      assertThat(result).isNotNull();
      verify(expectationCache).get(TEST_USER_IGN);
    }

    @Test
    @DisplayName("캐시 미스 - calculator 실행 후 저장")
    void cacheMiss_ShouldExecuteCalculatorAndStore() throws Exception {
      // Given: 캐시 미스
      when(expectationCache.get(TEST_USER_IGN)).thenReturn(null);

      EquipmentExpectationResponseV4 mockResponse = createMockResponse();
      when(executor.execute(any(), any(TaskContext.class))).thenReturn(mockResponse);

      // When: 캐시 조회
      Callable<EquipmentExpectationResponseV4> calculator = () -> mockResponse;
      EquipmentExpectationResponseV4 result =
          coordinator.getOrCalculate(TEST_USER_IGN, false, calculator);

      // Then: calculator 실행 후 캐시 저장
      assertThat(result).isNotNull();
      verify(expectationCache).put(eq(TEST_USER_IGN), any(String.class));
    }

    @Test
    @DisplayName("캐시 히트 - 빈 ValueWrapper(null inside) - Cache MISS로 처리")
    void cacheHitWithNullValue_ShouldTreatAsMiss() throws Exception {
      // Given: ValueWrapper 내부값이 null
      Cache.ValueWrapper wrapper = new SimpleValueWrapper(null);
      when(expectationCache.get(TEST_USER_IGN)).thenReturn(wrapper);

      EquipmentExpectationResponseV4 mockResponse = createMockResponse();
      when(executor.execute(any(), any(TaskContext.class))).thenReturn(mockResponse);

      // When: 캐시 조회
      Callable<EquipmentExpectationResponseV4> calculator = () -> mockResponse;
      EquipmentExpectationResponseV4 result =
          coordinator.getOrCalculate(TEST_USER_IGN, false, calculator);

      // Then: Cache MISS로 처리하여 calculator 실행
      assertThat(result).isNotNull();
      verify(expectationCache).put(eq(TEST_USER_IGN), any(String.class));
    }
  }

  @Nested
  @DisplayName("getGzipOrCalculate: 캐시 히트 시 ValueWrapper 언래핑")
  class GetGzipOrCalculateCacheHitTests {

    @Test
    @DisplayName("캐시 히트(Base64 String) - ValueWrapper 언래핑 후 GZIP 바이트 반환")
    void cacheHitWithBase64String_ShouldReturnGzipBytes() throws Exception {
      // Given: 캐시에 Base64 String이 ValueWrapper로 감싸져 저장됨
      Cache.ValueWrapper wrapper = new SimpleValueWrapper(TEST_COMPRESSED_BASE64);
      when(expectationCache.get(TEST_USER_IGN)).thenReturn(wrapper);

      // When: GZIP 캐시 조회
      EquipmentExpectationResponseV4 mockResponse = createMockResponse();
      Callable<EquipmentExpectationResponseV4> calculator = () -> mockResponse;
      byte[] result = coordinator.getGzipOrCalculate(TEST_USER_IGN, false, calculator);

      // Then: ValueWrapper 언래핑 후 Base64 디코딩된 GZIP 바이트 반환
      assertThat(result).isNotNull();
      assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("캐시 미스 - calculator 실행 후 GZIP 바이트 반환")
    void cacheMiss_ShouldExecuteCalculatorAndReturnGzipBytes() throws Exception {
      // Given: 캐시 미스
      when(expectationCache.get(TEST_USER_IGN)).thenReturn(null);

      EquipmentExpectationResponseV4 mockResponse = createMockResponse();
      when(executor.execute(any(), any(TaskContext.class))).thenReturn(mockResponse);

      // When: GZIP 캐시 조회
      Callable<EquipmentExpectationResponseV4> calculator = () -> mockResponse;
      byte[] result = coordinator.getGzipOrCalculate(TEST_USER_IGN, false, calculator);

      // Then: calculator 실행 후 GZIP 바이트 반환
      assertThat(result).isNotNull();
      verify(expectationCache).put(eq(TEST_USER_IGN), any(String.class));
    }

    @Test
    @DisplayName("캐시 히트 - 빈 문자열 - CacheDataNotFoundException 발생")
    void cacheHitWithEmptyString_ShouldThrowCacheDataNotFoundException() {
      // Given: 캐시에 빈 문자열이 ValueWrapper로 감싸져 저장됨
      Cache.ValueWrapper wrapper = new SimpleValueWrapper("");
      when(expectationCache.get(TEST_USER_IGN)).thenReturn(wrapper);

      // When & Then: CacheDataNotFoundException 발생
      EquipmentExpectationResponseV4 mockResponse = createMockResponse();
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
    void forceRefresh_ShouldIgnoreCacheAndUpdate() throws Exception {
      // Given: 캐시에 데이터가 있어도 무시
      Cache.ValueWrapper wrapper = new SimpleValueWrapper(TEST_COMPRESSED_BASE64);
      when(expectationCache.get(TEST_USER_IGN)).thenReturn(wrapper);

      EquipmentExpectationResponseV4 mockResponse = createMockResponse();
      when(executor.execute(any(), any(TaskContext.class))).thenReturn(mockResponse);

      // When: force=true로 조회
      Callable<EquipmentExpectationResponseV4> calculator = () -> mockResponse;
      EquipmentExpectationResponseV4 result =
          coordinator.getOrCalculate(TEST_USER_IGN, true, calculator);

      // Then: calculator 실행 후 캐시 갱신
      assertThat(result).isNotNull();
      verify(executor).execute(any(), any(TaskContext.class));
      verify(expectationCache).put(eq(TEST_USER_IGN), any(String.class));
    }
  }

  // ==================== Helper Methods ====================

  private EquipmentExpectationResponseV4 createMockResponse() {
    return EquipmentExpectationResponseV4.builder()
        .userIgn(TEST_USER_IGN)
        .fromCache(true)
        .totalExpectedCost("100,000")
        .totalCostText("100,000 메소")
        .maxPresetNo(1)
        .build();
  }

  private EquipmentExpectationResponseV4 decompressAndDeserialize(
      EquipmentExpectationResponseV4 response) {
    // 테스트용 Mock 반환 - 실제 압축/해제는 LogicExecutor 내부에서 처리
    return response;
  }
}
