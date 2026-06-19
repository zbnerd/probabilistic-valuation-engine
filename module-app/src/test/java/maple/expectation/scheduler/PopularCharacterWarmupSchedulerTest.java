package maple.expectation.scheduler;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import kotlin.jvm.functions.Function0;
import maple.expectation.core.port.out.CacheWarmupPort;
import maple.expectation.core.port.out.PopularCharacterTrackerPort;
import maple.expectation.error.exception.DistributedLockException;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.lock.LockStrategy;
import maple.expectation.infrastructure.scheduler.PopularCharacterWarmupScheduler;
import maple.expectation.support.TestLogicExecutors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * PopularCharacterWarmupScheduler 단위 테스트 (Issue #194)
 *
 * <h4>경량 테스트 (CLAUDE.md Section 25)</h4>
 *
 * <p>웜업 스케줄러 메서드를 직접 호출하여 동작을 검증합니다.
 *
 * <h4>테스트 범위</h4>
 *
 * <ul>
 *   <li>dailyWarmup: 매일 새벽 5시 웜업
 *   <li>initialWarmup: 서버 시작 후 30초 웜업
 *   <li>분산 락 사용
 *   <li>인기 캐릭터 조회 및 캐시 프리로딩
 * </ul>
 *
 * <h4>ADR-003 Hexagonal Architecture</h4>
 *
 * <p>스케줄러가 Port 인터페이스에 의존하도록 리팩토링됨:
 *
 * <ul>
 *   <li>PopularCharacterTrackerPort - 인기 캐릭터 조회
 *   <li>CacheWarmupPort - 캐시 웜업
 * </ul>
 */
@Tag("unit")
class PopularCharacterWarmupSchedulerTest {

  private PopularCharacterTrackerPort popularCharacterTracker;
  private CacheWarmupPort cacheWarmupPort;
  private LockStrategy lockStrategy;
  private LogicExecutor executor;
  private MeterRegistry meterRegistry;
  private PopularCharacterWarmupScheduler scheduler;

  @BeforeEach
  void setUp() {
    popularCharacterTracker = mock(PopularCharacterTrackerPort.class);
    cacheWarmupPort = mock(CacheWarmupPort.class);
    lockStrategy = mock(LockStrategy.class);
    executor = TestLogicExecutors.passThrough();
    meterRegistry = new SimpleMeterRegistry(); // 실제 MeterRegistry 사용

    scheduler =
        new PopularCharacterWarmupScheduler(
            popularCharacterTracker, cacheWarmupPort, lockStrategy, executor, meterRegistry);

    // Set @Value fields via reflection
    ReflectionTestUtils.setField(scheduler, "topCount", 50);
    ReflectionTestUtils.setField(scheduler, "delayBetweenMs", 0L); // 테스트에서는 지연 없음
  }

  @Nested
  @DisplayName("dailyWarmup")
  class DailyWarmupTest {

    @SuppressWarnings("unchecked")
    private void stubLockSuccess() {
      given(lockStrategy.executeWithLockAsync(anyString(), anyLong(), anyLong(), any(Function0.class)))
          .willAnswer(
              invocation -> {
                Function0<CompletableFuture<Object>> supplier = invocation.getArgument(3);
                return supplier.invoke();
              });
    }

    @Test
    @DisplayName("분산 락 획득 후 웜업 실행")
    void shouldExecuteWarmupWithLock() throws Throwable {
      // given
      stubLockSuccess();
      given(popularCharacterTracker.getYesterdayTopCharacters(50))
          .willReturn(List.of("User1", "User2"));

      // when
      scheduler.dailyWarmup();

      // then
      verify(lockStrategy)
          .executeWithLockAsync(
              eq("popular-warmup-lock"), eq(0L), eq(300L), any(Function0.class));
    }

    @Test
    @DisplayName("락 획득 실패 시 스킵")
    void whenLockFailed_shouldSkip() throws Throwable {
      // given - 완료되었지만 DistributedLockException으로 실패하는 future 반환
      given(lockStrategy.executeWithLockAsync(anyString(), anyLong(), anyLong(), any(Function0.class)))
          .willAnswer(
              invocation ->
                  CompletableFuture.failedFuture(new DistributedLockException("Lock failed")));

      // when
      scheduler.dailyWarmup();

      // then
      verify(popularCharacterTracker, never()).getYesterdayTopCharacters(anyInt());
    }
  }

  @Nested
  @DisplayName("initialWarmup")
  class InitialWarmupTest {

    @SuppressWarnings("unchecked")
    private void stubLockSuccess() {
      given(lockStrategy.executeWithLockAsync(anyString(), anyLong(), anyLong(), any(Function0.class)))
          .willAnswer(
              invocation -> {
                Function0<CompletableFuture<Object>> supplier = invocation.getArgument(3);
                return supplier.invoke();
              });
    }

    @Test
    @DisplayName("서버 시작 후 웜업 실행")
    void shouldExecuteInitialWarmup() throws Throwable {
      // given
      stubLockSuccess();
      given(popularCharacterTracker.getYesterdayTopCharacters(50)).willReturn(List.of("InitUser1"));

      // when
      scheduler.initialWarmup();

      // then
      verify(lockStrategy)
          .executeWithLockAsync(
              eq("popular-warmup-lock"), anyLong(), anyLong(), any(Function0.class));
    }
  }

  @Nested
  @DisplayName("웜업 로직")
  class WarmupLogicTest {

    @SuppressWarnings("unchecked")
    private void stubLockSuccess() {
      given(lockStrategy.executeWithLockAsync(anyString(), anyLong(), anyLong(), any(Function0.class)))
          .willAnswer(
              invocation -> {
                Function0<CompletableFuture<Object>> supplier = invocation.getArgument(3);
                return supplier.invoke();
              });
    }

    @Test
    @DisplayName("인기 캐릭터 목록 조회")
    void shouldGetTopCharacters() throws Throwable {
      // given
      stubLockSuccess();
      given(popularCharacterTracker.getYesterdayTopCharacters(50))
          .willReturn(List.of("Top1", "Top2", "Top3"));

      // when
      scheduler.dailyWarmup();

      // then
      verify(popularCharacterTracker).getYesterdayTopCharacters(50);
    }

    @Test
    @DisplayName("각 캐릭터에 대해 warmup 호출")
    void shouldWarmupEachCharacter() throws Throwable {
      // given
      List<String> topCharacters = List.of("Char1", "Char2", "Char3");
      stubLockSuccess();
      given(popularCharacterTracker.getYesterdayTopCharacters(50)).willReturn(topCharacters);

      // when
      scheduler.dailyWarmup();

      // then - 각 캐릭터에 대해 웜업 호출
      verify(cacheWarmupPort, times(3)).warmup(anyString(), eq(false));
    }

    @Test
    @DisplayName("인기 캐릭터가 없으면 웜업 스킵")
    void whenNoCharacters_shouldSkipWarmup() throws Throwable {
      // given
      stubLockSuccess();
      given(popularCharacterTracker.getYesterdayTopCharacters(50)).willReturn(List.of());

      // when
      scheduler.dailyWarmup();

      // then
      verify(cacheWarmupPort, never()).warmup(anyString(), anyBoolean());
    }

    @Test
    @DisplayName("개별 캐릭터 웜업 실패 시 다음 캐릭터 계속 처리")
    void whenCharacterFails_shouldContinueWithNext() throws Throwable {
      // given
      List<String> topCharacters = List.of("Fail1", "Success2", "Fail3");
      stubLockSuccess();
      given(popularCharacterTracker.getYesterdayTopCharacters(50)).willReturn(topCharacters);

      // 첫 번째와 세 번째 호출은 예외
      doThrow(new RuntimeException("API Error"))
          .doNothing()
          .doThrow(new RuntimeException("API Error"))
          .when(cacheWarmupPort)
          .warmup(anyString(), anyBoolean());

      // when
      scheduler.dailyWarmup();

      // then - 모든 캐릭터에 대해 시도
      verify(cacheWarmupPort, times(3)).warmup(anyString(), eq(false));
    }
  }
}
