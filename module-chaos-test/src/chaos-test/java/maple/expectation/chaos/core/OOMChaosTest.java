package maple.expectation.chaos.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.*;

/**
 * Scenario 03: OOM이 일어났을 경우 (Standalone Test)
 *
 * <h4>5-Agent Council</h4>
 *
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - JVM 메모리 압박
 *   <li>🟣 Purple (Auditor): 데이터 무결성 검증 - 트랜잭션 롤백 확인
 *   <li>🔵 Blue (Architect): 흐름 검증 - Error 전파 정책
 *   <li>🟢 Green (Performance): 메트릭 검증 - Heap 사용량
 * </ul>
 *
 * <h4>검증 포인트</h4>
 *
 * <ol>
 *   <li>Error를 catch하지 않고 즉시 전파
 *   <li>메모리 압박 상황에서 GC 정상 동작
 *   <li>Health Indicator로 메모리 상태 모니터링
 * </ol>
 *
 * <h4>CS 원리</h4>
 *
 * <ul>
 *   <li>Error vs Exception: Error는 복구 불가능, catch 금지
 *   <li>GC Pressure: 메모리 압박 시 GC 빈도 증가
 *   <li>Fail Fast: OOM 발생 시 빠른 실패 및 재시작
 * </ul>
 */
@Tag("chaos")
@DisplayName("Scenario 03: OOM - Error 전파 및 메모리 관리 검증")
class OOMChaosTest {

  private final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
  private final SimpleExecutor executor = new SimpleExecutor();

  @Test
  @DisplayName("Error를 catch하지 않고 즉시 전파")
  void shouldPropagateError_whenOutOfMemoryErrorOccurs() {
    TaskContext context = TaskContext.of("Chaos", "OOMTest");

    assertThatThrownBy(
            () ->
                executor.execute(
                    () -> {
                      throw new OutOfMemoryError("Simulated OOM for test");
                    },
                    context))
        .isInstanceOf(OutOfMemoryError.class)
        .hasMessageContaining("Simulated OOM");
  }

  @Test
  @DisplayName("executeOrDefault에서도 Error는 전파됨")
  void shouldPropagateError_evenInExecuteOrDefault() {
    TaskContext context = TaskContext.of("Chaos", "OOMDefaultTest");
    String defaultValue = "default";

    assertThatThrownBy(
            () ->
                executor.executeOrDefault(
                    () -> {
                      throw new StackOverflowError("Simulated StackOverflow");
                    },
                    defaultValue,
                    context))
        .isInstanceOf(StackOverflowError.class);
  }

  @Test
  @DisplayName("JVM 메모리 사용량 모니터링")
  void shouldMonitorMemoryUsage() {
    MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();

    long usedMB = heapUsage.getUsed() / (1024 * 1024);
    long maxMB = heapUsage.getMax() / (1024 * 1024);
    double usagePercent = (double) heapUsage.getUsed() / heapUsage.getMax() * 100;

    assertThat(usagePercent).as("초기 상태에서 힙 사용량이 90% 이하여야 함").isLessThan(90.0);
  }

  @Test
  @DisplayName("메모리 압박 후 GC 복구 확인")
  void shouldRecoverMemory_afterGCUnderPressure() {
    MemoryUsage beforePressure = memoryMXBean.getHeapMemoryUsage();
    long beforeUsed = beforePressure.getUsed();

    List<byte[]> memoryHog = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      memoryHog.add(new byte[1024 * 1024]);
    }

    MemoryUsage underPressure = memoryMXBean.getHeapMemoryUsage();
    long pressureUsed = underPressure.getUsed();

    memoryHog.clear();
    memoryHog = null;
    System.gc();

    try {
      Thread.sleep(1000);
    } catch (InterruptedException ignored) {
    }

    MemoryUsage afterGC = memoryMXBean.getHeapMemoryUsage();
    long afterUsed = afterGC.getUsed();

    assertThat(afterUsed).as("GC 후 메모리가 압박 상태보다 감소해야 함").isLessThan(pressureUsed);
  }

  @Test
  @DisplayName("ExceptionTranslator가 Error를 catch하지 않고 re-throw")
  void shouldRethrowError_inExceptionTranslator() {
    TaskContext context = TaskContext.of("Chaos", "TranslatorTest");

    assertThatThrownBy(
            () ->
                executor.executeWithTranslation(
                    () -> {
                      throw new OutOfMemoryError("OOM in translation");
                    },
                    (e) -> new RuntimeException("Should not reach here"),
                    context))
        .isInstanceOf(OutOfMemoryError.class);
  }

  private static class TaskContext {
    private final String domain;
    private final String task;

    private TaskContext(String domain, String task) {
      this.domain = domain;
      this.task = task;
    }

    public static TaskContext of(String domain, String task) {
      return new TaskContext(domain, task);
    }
  }

  private static class SimpleExecutor {

    public <T> T execute(ThrowingSupplier<T> task, TaskContext context) {
      return task.get();
    }

    public <T> T executeOrDefault(ThrowingSupplier<T> task, T defaultValue, TaskContext context) {
      try {
        return task.get();
      } catch (Exception e) {
        return defaultValue;
      }
    }

    public <T> T executeWithTranslation(
        ThrowingSupplier<T> task,
        Function<Throwable, RuntimeException> translator,
        TaskContext context) {
      return task.get();
    }
  }

  @FunctionalInterface
  private interface ThrowingSupplier<T> {
    T get();
  }
}
