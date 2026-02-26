package maple.expectation.infrastructure.config

import maple.expectation.infrastructure.concurrency.SingleFlightExecutor
import org.springframework.stereotype.Component
import java.util.concurrent.Executor
import java.util.function.Function

/**
 * Factory for creating SingleFlightExecutor instances.
 *
 * Supports DIP (Dependency Inversion Principle) by allowing injection instead of direct
 * instantiation with `new`.
 *
 * @param T the response type
 * @see maple.expectation.service.v2.EquipmentService
 */
@Component
class SingleFlightExecutorFactory {

    /**
     * Create a new SingleFlightExecutor with the specified parameters.
     *
     * @param timeoutSeconds timeout in seconds
     * @param executor executor for async execution
     * @param fallback fallback function on timeout
     * @param T response type
     * @return configured SingleFlightExecutor
     */
    fun <T> create(
        timeoutSeconds: Long,
        executor: Executor,
        fallback: Function<String, T>
    ): SingleFlightExecutor<T> {
        return SingleFlightExecutor(timeoutSeconds.toInt(), executor, fallback)
    }
}
