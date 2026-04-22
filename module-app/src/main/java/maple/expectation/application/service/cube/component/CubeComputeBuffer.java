package maple.expectation.application.service.cube.component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import maple.expectation.core.dto.cube.CubeComputeKey;
import maple.expectation.core.port.inbound.BatchComputeBuffer;
import org.springframework.stereotype.Component;

/**
 * Thread-safe batch compute buffer backed by ConcurrentHashMap.
 *
 * <p>Memoizes cube probability computation results within a single batch
 * to avoid redundant calculations for identical keys.
 *
 * @see BatchComputeBuffer
 * @see CubeComputeKey
 */
@Component
public class CubeComputeBuffer implements BatchComputeBuffer {

    private final ConcurrentHashMap<CubeComputeKey, Double> cache = new ConcurrentHashMap<>();

    public Double getOrCompute(CubeComputeKey key, Supplier<Double> compute) {
        return cache.computeIfAbsent(key, k -> compute.get());
    }

    @Override
    public void clear() {
        cache.clear();
    }

    public int size() {
        return cache.size();
    }
}
