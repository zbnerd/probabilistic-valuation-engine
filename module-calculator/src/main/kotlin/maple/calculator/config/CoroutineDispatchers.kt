package maple.calculator.config

import jakarta.annotation.PreDestroy
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Coroutine dispatcher for blocking IO in the calculator pipeline.
 *
 * Backed by `Executors.newVirtualThreadPerTaskExecutor()`: each
 * `withContext(vtDispatcher) { ... }` call parks a virtual thread instead of a
 * platform carrier thread, so blocking file IO (openInputStream, gzip
 * decompress, file write, disk stat) does not starve the Kafka listener
 * platform thread.
 *
 * Replaces the previous `Dispatchers.IO` usage in the chunk pipeline. Pooling
 * a fixed number of platform threads (as `Dispatchers.IO` does) was the root
 * cause of 5+ minute stuck-chunk hangs: when listener threads blocked on
 * file IO, the next message could not be polled, lag accumulated, and Spring
 * Kafka's poll-timeout eventually killed the container.
 *
 * Virtual threads are cheap to create, so an unbounded pool is safe.
 * Default virtual-thread naming (e.g. `VirtualThread[#42]/runnable@...`) is
 * kept — it is identifiable enough in thread dumps and there is no
 * `Executors.newVirtualThreadPerTaskExecutor(ThreadFactory)` overload on JDK
 * 21 to inject a custom factory.
 *
 * @see maple.calculator.pipeline.SnapshotChunkPipeline stage 1
 * @see maple.calculator.processor.SnapshotChunkProcessor file IO wrap
 * @see maple.calculator.CalculatorChunkProcessingCoordinator disk-stat wrap
 */
@Configuration
class CoroutineDispatchers {

    private val executor = Executors.newVirtualThreadPerTaskExecutor()

    @Bean
    fun vtDispatcher(): CoroutineDispatcher = executor.asCoroutineDispatcher()

    @PreDestroy
    fun shutdown() {
        executor.close()
    }
}
