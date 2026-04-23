package maple.expectation.application.service.cube.component;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import maple.expectation.core.dto.cube.CubeComputeKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CubeComputeBufferTest {

    private CubeComputeBuffer buffer;

    @BeforeEach
    void setUp() {
        buffer = new CubeComputeBuffer();
    }

    @Test
    void getOrCompute_returnsCachedResult() {
        CubeComputeKey key = new CubeComputeKey("BLACK", 160, "무기", "유니크", null, null, true, "csv-v1.0");
        AtomicInteger callCount = new AtomicInteger(0);

        Double result1 = buffer.getOrCompute(key, () -> { callCount.incrementAndGet(); return 42.0; });
        Double result2 = buffer.getOrCompute(key, () -> { callCount.incrementAndGet(); return 99.0; });

        assertThat(result1).isEqualTo(42.0);
        assertThat(result2).isEqualTo(42.0);
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void clear_removesAllEntries() {
        CubeComputeKey key = new CubeComputeKey("BLACK", 160, "무기", "유니크", null, null, true, "csv-v1.0");
        buffer.getOrCompute(key, () -> 42.0);
        assertThat(buffer.size()).isEqualTo(1);

        buffer.clear();
        assertThat(buffer.size()).isEqualTo(0);
    }

    @Test
    void differentKeys_computeIndependently() {
        CubeComputeKey key1 = new CubeComputeKey("BLACK", 160, "무기", "유니크", null, null, true, "csv-v1.0");
        CubeComputeKey key2 = new CubeComputeKey("ADDITIONAL", 160, "무기", "유니크", null, null, true, "csv-v1.0");

        buffer.getOrCompute(key1, () -> 42.0);
        buffer.getOrCompute(key2, () -> 84.0);

        assertThat(buffer.size()).isEqualTo(2);
        assertThat(buffer.getOrCompute(key1, () -> 0.0)).isEqualTo(42.0);
        assertThat(buffer.getOrCompute(key2, () -> 0.0)).isEqualTo(84.0);
    }
}
