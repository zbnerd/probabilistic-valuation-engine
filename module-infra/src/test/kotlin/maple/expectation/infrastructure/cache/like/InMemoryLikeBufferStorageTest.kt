package maple.expectation.infrastructure.cache.like

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class InMemoryLikeBufferStorageTest {

    private lateinit var storage: InMemoryLikeBufferStorage

    @BeforeEach
    fun setUp() {
        storage = InMemoryLikeBufferStorage(SimpleMeterRegistry(), maxSize = 1000)
    }

    @Test
    @DisplayName("increment 후 fetchAndClear로 값을 가져올 수 있다")
    fun incrementAndFetchAndClear() {
        storage.increment("user1", 5)
        storage.increment("user2", 3)

        val result = storage.fetchAndClear(10)

        assertThat(result).containsEntry("user1", 5L).containsEntry("user2", 3L)
    }

    @Test
    @DisplayName("fetchAndClear 후 카운터는 0으로 초기화된다")
    fun fetchAndClearResetsCounters() {
        storage.increment("user1", 10)
        storage.fetchAndClear(10)

        assertThat(storage.get("user1")).isEqualTo(0L)
    }

    @Test
    @DisplayName("빈 버퍼에서 fetchAndClear는 빈 맵을 반환한다")
    fun fetchAndClearEmptyBuffer() {
        val result = storage.fetchAndClear(10)

        assertThat(result).isEmpty()
    }

    @Test
    @DisplayName("limit보다 많은 counter가 있으면 limit 개수만 반환한다")
    fun fetchAndClearRespectsLimit() {
        storage.increment("user1", 1)
        storage.increment("user2", 2)
        storage.increment("user3", 3)

        val result = storage.fetchAndClear(2)

        assertThat(result).hasSize(2)
    }

    @Test
    @DisplayName("fetchAndClear는 값이 0인 카운터를 건너뛴다")
    fun fetchAndClearSkipsZeroCounters() {
        storage.increment("user1", 5)
        storage.getCounter("user2")  // counter만 생성, 값은 0

        val result = storage.fetchAndClear(10)

        assertThat(result).hasSize(1)
        assertThat(result["user1"]).isEqualTo(5L)
    }

    @Test
    @DisplayName("동시 increment와 fetchAndClear에서 카운트 누락이 없다")
    fun concurrentIncrementAndFetchAndClear() {
        val incrementThreads = 5
        val flushThreads = 3
        val incrementsPerThread = 200
        val totalExpected = (incrementThreads * incrementsPerThread).toLong()
        val collectedResults = ConcurrentHashMap<String, Long>()
        val barrier = java.util.concurrent.CyclicBarrier(incrementThreads + flushThreads)
        val executor = Executors.newFixedThreadPool(incrementThreads + flushThreads)

        try {
            // increment 스레드: barrier에서 동시 시작
            for (i in 0 until incrementThreads) {
                executor.submit {
                    barrier.await()
                    for (j in 0 until incrementsPerThread) {
                        storage.increment("user1", 1)
                    }
                }
            }

            // fetchAndClear 스레드: barrier에서 동시 시작 (increment와 병렬)
            for (i in 0 until flushThreads) {
                executor.submit {
                    barrier.await()
                    repeat(20) {
                        val batch = storage.fetchAndClear(1000)
                        batch.forEach { (k, v) -> collectedResults.merge(k, v) { a, b -> a + b } }
                    }
                }
            }

            executor.shutdown()
            executor.awaitTermination(5, TimeUnit.SECONDS)

            // 남은 값 수집
            val finalResult = storage.fetchAndClear(1000)
            finalResult.forEach { (k, v) -> collectedResults.merge(k, v) { a, b -> a + b } }

            // 총합 검증: 수집된 모든 값의 합 == 총 increment 수
            val totalCollected = collectedResults.values.sum()
            assertThat(totalCollected).isEqualTo(totalExpected)
        } finally {
            if (!executor.isTerminated) {
                executor.shutdownNow()
            }
        }
    }

    @Test
    @DisplayName("getAllCounters로 모든 카운터를 조회할 수 있다")
    fun getAllCounters() {
        storage.increment("user1", 5)
        storage.increment("user2", 3)

        val counters = storage.getAllCounters()

        assertThat(counters).containsEntry("user1", 5L).containsEntry("user2", 3L)
    }

    @Test
    @DisplayName("getBufferSize로 버퍼 크기를 확인할 수 있다")
    fun getBufferSize() {
        storage.increment("user1", 1)
        storage.increment("user2", 1)

        assertThat(storage.getBufferSize()).isEqualTo(2)
    }
}
