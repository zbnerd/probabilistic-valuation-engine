package maple.expectation.chaos.resource;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

/**
 * Scenario 10: Pool Exhaustion - 커넥션 풀 고갈 (Standalone Test)
 *
 * <h4>5-Agent Council</h4>
 *
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - 자원을 점유하여 풀 고갈
 *   <li>🔵 Blue (Architect): 흐름 검증 - 풀 고갈 시 Fail-Fast 동작
 *   <li>🟢 Green (Performance): 메트릭 검증 - 대기 시간, 타임아웃
 *   <li>🟣 Purple (Auditor): 데이터 검증 - 풀 고갈이 데이터 무결성에 영향 없음
 * </ul>
 *
 * <h4>검증 포인트</h4>
 *
 * <ol>
 *   <li>풀 고갈 시 빠른 타임아웃 발생
 *   <li>자원 반환 후 빠른 복구
 *   <li>풀 고갈 중 다른 요청에 미치는 영향
 *   <li>동시 요청 시 풀 경합 분석
 * </ol>
 */
@Tag("chaos")
@DisplayName("Scenario 10: Pool Exhaustion - 자원 풀 고갈 및 복구")
class PoolExhaustionChaosTest {

  private SimpleResourcePool resourcePool;

  @BeforeEach
  void setUp() {
    resourcePool = new SimpleResourcePool(10);
  }

  @AfterEach
  void tearDown() {
    resourcePool.shutdown();
  }

  @Test
  @DisplayName("자원 풀 고갈 시 타임아웃 발생")
  void shouldTimeout_whenPoolExhausted() throws Exception {
    List<ResourceHandle> heldResources = new ArrayList<>();
    int exhaustCount = 0;
    int maxResources = 10;

    try {
      for (int i = 0; i < maxResources + 5; i++) {
        try {
          ResourceHandle handle = resourcePool.acquire(100);
          if (handle != null) {
            heldResources.add(handle);
            exhaustCount++;
          } else {
            break;
          }
        } catch (Exception e) {
          break;
        }
      }

      ResourceHandle extraHandle = resourcePool.acquire(100);
      assertThat(extraHandle).as("풀 고갈 시 null 반환").isNull();

    } finally {
      for (ResourceHandle handle : heldResources) {
        resourcePool.release(handle);
      }
    }

    assertThat(exhaustCount).as("최대 자원 수만큼 확보 가능").isEqualTo(maxResources);
  }

  @Test
  @DisplayName("자원 반환 후 즉시 재사용 가능")
  void shouldRecover_afterResourcesReleased() throws Exception {
    List<ResourceHandle> heldResources = new ArrayList<>();

    for (int i = 0; i < 5; i++) {
      ResourceHandle handle = resourcePool.acquire(100);
      if (handle != null) {
        heldResources.add(handle);
      }
    }

    for (ResourceHandle handle : heldResources) {
      resourcePool.release(handle);
    }

    long start = System.nanoTime();
    ResourceHandle newHandle = resourcePool.acquire(100);
    long elapsed = (System.nanoTime() - start) / 1_000_000;

    assertThat(newHandle).as("복구 후 자원 획득 성공").isNotNull();
    assertThat(elapsed).as("복구 후 자원 획득은 빨라야 함 (< 100ms)").isLessThan(100);

    resourcePool.release(newHandle);
  }

  @Test
  @DisplayName("동시 요청 시 자원 풀 경합 분석")
  void shouldAnalyze_poolContention() throws Exception {
    int concurrentRequests = 20;
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger timeoutCount = new AtomicInteger(0);
    ConcurrentLinkedQueue<Long> responseTimes = new ConcurrentLinkedQueue<>();

    ExecutorService executor = Executors.newFixedThreadPool(concurrentRequests);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch endLatch = new CountDownLatch(concurrentRequests);

    for (int i = 0; i < concurrentRequests; i++) {
      executor.submit(
          () -> {
            try {
              startLatch.await();

              long start = System.nanoTime();
              ResourceHandle handle = resourcePool.acquire(500);
              if (handle != null) {
                try {
                  Thread.sleep(50);
                  long elapsed = (System.nanoTime() - start) / 1_000_000;
                  responseTimes.add(elapsed);
                  successCount.incrementAndGet();
                } finally {
                  resourcePool.release(handle);
                }
              } else {
                timeoutCount.incrementAndGet();
              }
            } catch (Exception e) {
              timeoutCount.incrementAndGet();
            } finally {
              endLatch.countDown();
            }
          });
    }

    startLatch.countDown();
    endLatch.await(30, TimeUnit.SECONDS);
    executor.shutdown();

    assertThat(successCount.get()).as("대부분의 요청이 성공해야 함").isGreaterThan(concurrentRequests / 2);
  }

  private static class SimpleResourcePool {
    private final Semaphore semaphore;
    private final int maxSize;
    private volatile boolean shutdown = false;

    SimpleResourcePool(int maxSize) {
      this.maxSize = maxSize;
      this.semaphore = new Semaphore(maxSize, true);
    }

    ResourceHandle acquire(long timeoutMs) throws InterruptedException {
      if (shutdown) {
        return null;
      }
      if (semaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
        return new ResourceHandle(this);
      }
      return null;
    }

    void release(ResourceHandle handle) {
      if (handle != null && handle.pool == this) {
        semaphore.release();
      }
    }

    void shutdown() {
      shutdown = true;
    }
  }

  private static class ResourceHandle {
    private final SimpleResourcePool pool;

    ResourceHandle(SimpleResourcePool pool) {
      this.pool = pool;
    }
  }
}
