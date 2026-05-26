package maple.expectation.application.service.cube.component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import maple.expectation.core.dto.cube.CubeComputeKey;
import maple.expectation.core.port.inbound.BatchComputeBuffer;
import org.springframework.stereotype.Component;

/**
 * Thread-safe batch compute buffer backed by ConcurrentHashMap.
 *
 * <p>Memoizes cube probability computation results within a single batch to avoid redundant
 * calculations for identical keys.
 *
 * @see BatchComputeBuffer
 * @see CubeComputeKey
 */
@Component
public class CubeComputeBuffer implements BatchComputeBuffer {

  private final ConcurrentHashMap<CubeComputeKey, Double> cache = new ConcurrentHashMap<>();
  private final java.util.concurrent.atomic.AtomicInteger hits =
      new java.util.concurrent.atomic.AtomicInteger(0);
  private final java.util.concurrent.atomic.AtomicInteger misses =
      new java.util.concurrent.atomic.AtomicInteger(0);

  public Double getOrCompute(CubeComputeKey key, Supplier<Double> compute) {
    Double existing = cache.get(key);
    if (existing != null) {
      hits.incrementAndGet();
      return existing;
    }
    Double result =
        cache.computeIfAbsent(
            key,
            k -> {
              misses.incrementAndGet();
              return compute.get();
            });
    return result;
  }

  @Override
  public void clear() {
    cache.clear();
    hits.set(0);
    misses.set(0);
  }

  @Override
  public BatchComputeBuffer.BufferStats stats() {
    return BatchComputeBuffer.BufferStats.of(hits.get(), misses.get(), cache.size());
  }

  public int size() {
    return cache.size();
  }
}
