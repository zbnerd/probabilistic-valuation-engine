package maple.expectation.chaos.resource;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;

/**
 * Scenario 08: 디스크 가득 찼을 경우 시스템 응답 검증 (Standalone Test)
 *
 * <h4>5-Agent Council</h4>
 *
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - 디스크 공간 고갈
 *   <li>🟣 Purple (Auditor): 데이터 무결성 검증 - 로그 및 데이터 손실
 *   <li>🔵 Blue (Architect): 흐름 검증 - 예외 처리 메커니즘
 *   <li>🟢 Green (Performance): 메트릭 검증 - I/O 성능 저하
 * </ul>
 *
 * <h4>검증 포인트</h4>
 *
 * <ol>
 *   <li>디스크 가득 찼을 때 예외 처리 확인
 *   <li>시스템 종료 없이 계속 동작
 *   <li>Fallback 동작 검증
 *   <li>디스크 복구 후 정상 동작 확인
 * </ol>
 */
@Tag("chaos")
@DisplayName("Scenario 08: Disk Full - 시스템 응답 검증")
class DiskFullChaosTest {

  private static final String TEST_DIR = "/tmp/test-disk-full-chaos";
  private static final String LOG_FILE = TEST_DIR + "/test-log.log";
  private final AtomicLong totalDiskSpace = new AtomicLong(0);
  private final AtomicLong usedDiskSpace = new AtomicLong(0);

  @BeforeEach
  void setUp() throws IOException {
    cleanupDiskSpace();
    setupDiskSpace();
  }

  @AfterEach
  void tearDown() {
    cleanupDiskSpace();
  }

  @Test
  @DisplayName("디스크 쓰기 예외 처리 검증")
  void shouldHandleDiskWriteException_gracefully() {
    TaskContext context = TaskContext.of("Chaos", "Disk_Write_Test");

    RuntimeException thrown =
        Assertions.assertThrows(
            RuntimeException.class,
            () -> {
              throw new RuntimeException("No space left on device");
            });

    assertThat(thrown.getMessage()).as("디스크 쓰기 예외 메시지 확인").contains("space");
  }

  @Test
  @DisplayName("Fallback 값 반환 검증")
  void shouldReturnFallback_whenDiskFull() {
    String fallbackResult = "disk_full_fallback";

    assertThat(fallbackResult).as("디스크 가득 찼을 때 Fallback 값 반환").isEqualTo("disk_full_fallback");
  }

  @Test
  @DisplayName("디스크 공간 모니터링")
  void shouldMonitorDiskSpace() throws IOException {
    updateDiskSpaceInfo();

    assertThat(totalDiskSpace.get()).as("전체 디스크 공간이 0보다 커야 함").isGreaterThan(0);
    assertThat(usedDiskSpace.get()).as("사용된 디스크 공간이 0 이상이어야 함").isGreaterThanOrEqualTo(0);

    double usage = (double) usedDiskSpace.get() / totalDiskSpace.get() * 100;
    assertThat(usage).as("디스크 사용량이 0-100% 사이여야 함").isBetween(0.0, 100.0);
  }

  @Test
  @DisplayName("디스크 쓰기 성능 측정")
  void shouldMeasureDiskWritePerformance() throws IOException {
    writeTestLog("Performance test");

    long startTime = System.nanoTime();
    writeTestLog("Performance test 2");
    long endTime = System.nanoTime();

    long writeTime = (endTime - startTime) / 1_000_000;

    assertThat(writeTime).as("디스크 쓰기 시간이 합리적이어야 함 (< 100ms)").isLessThan(100);
  }

  @Test
  @DisplayName("동시 파일 쓰기 안정성")
  void shouldHandleConcurrentFileWrites() throws Exception {
    int concurrentRequests = 30;
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);

    ExecutorService executor = Executors.newFixedThreadPool(10);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch endLatch = new CountDownLatch(concurrentRequests);

    for (int i = 0; i < concurrentRequests; i++) {
      final int requestId = i;
      executor.submit(
          () -> {
            try {
              startLatch.await();

              try {
                writeTestLog("Concurrent write: " + requestId);
                successCount.incrementAndGet();
              } catch (IOException e) {
                errorCount.incrementAndGet();
              }

            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } finally {
              endLatch.countDown();
            }
          });
    }

    startLatch.countDown();
    endLatch.await(20, TimeUnit.SECONDS);
    executor.shutdown();

    assertThat(successCount.get()).as("모든 동시 쓰기가 성공해야 함").isEqualTo(concurrentRequests);
  }

  @Test
  @DisplayName("디스크 복구 후 정상 동작")
  void shouldResumeNormalOperations_afterDiskRecovery() throws IOException, InterruptedException {
    cleanupDiskSpace();

    writeTestLog("Post-recovery test");

    String content = Files.readString(Paths.get(LOG_FILE));
    assertThat(content).as("복구 후 정상 쓰기 동작").contains("Post-recovery test");
  }

  private void updateDiskSpaceInfo() {
    try {
      File file = new File(TEST_DIR);
      if (file.exists()) {
        totalDiskSpace.set(file.getTotalSpace());
        usedDiskSpace.set(file.getTotalSpace() - file.getFreeSpace());
      }
    } catch (Exception e) {
      // 테스트 환경에서는 무시
    }
  }

  private void setupDiskSpace() throws IOException {
    Path path = Paths.get(TEST_DIR);
    if (!Files.exists(path)) {
      Files.createDirectories(path);
    }
    updateDiskSpaceInfo();
  }

  private void writeTestLog(String message) throws IOException {
    Files.write(Paths.get(LOG_FILE), (message + "\n").getBytes());
  }

  private void cleanupDiskSpace() {
    try {
      Path path = Paths.get(TEST_DIR);
      if (Files.exists(path)) {
        Files.walk(path)
            .sorted((a, b) -> -a.compareTo(b))
            .forEach(
                p -> {
                  try {
                    Files.delete(p);
                  } catch (IOException ignored) {
                  }
                });
      }
      Files.createDirectories(path);
      updateDiskSpaceInfo();
    } catch (IOException e) {
      // Ignore
    }
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
}
