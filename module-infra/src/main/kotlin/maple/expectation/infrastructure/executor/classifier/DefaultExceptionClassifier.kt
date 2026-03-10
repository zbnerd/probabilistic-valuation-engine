@file:JvmName("DefaultExceptionClassifier")

package maple.expectation.infrastructure.executor.classifier

import maple.expectation.error.exception.base.ClientBaseException
import maple.expectation.error.exception.base.ServerBaseException
import org.springframework.stereotype.Component

/**
 * Default implementation of ExceptionClassifier.
 *
 * <p>Provides standard classification based on exception hierarchy:
 * <ul>
 *   <li><b>ClientBaseException</b> (4xx) → IGNORE: Business exceptions don't trigger circuit breaker</li>
 *   <li><b>ServerBaseException</b> (5xx) → RECORD: System exceptions trigger circuit breaker</li>
 *   <li><b>Other exceptions</b> → DEFAULT: Use circuit breaker's default behavior</li>
 * </ul>
 *
 * <h3>Design Principle:</h3>
 * <p>This classifier enables domain exceptions to remain pure (no infrastructure dependencies)
 * while still participating in circuit breaker patterns. The classification logic is
 * centralized here rather than scattered across exception classes via marker interfaces.
 *
 * <h3>Migration from Marker Interfaces:</h3>
 * <p>Previously, exceptions implemented marker interfaces directly:
 * <pre>
 * // Old approach (deprecated):
 * abstract class ClientBaseException : BaseException, CircuitBreakerIgnoreMarker
 * abstract class ServerBaseException : BaseException, CircuitBreakerRecordMarker
 *
 * // New approach (recommended):
 * abstract class ClientBaseException : BaseException  // No marker!
 * abstract class ServerBaseException : BaseException  // No marker!
 * </pre>
 *
 * @see ExceptionClassifier
 * @see CircuitBreakerClassification
 * @see ClientBaseException
 * @see ServerBaseException
 */
@Component
class DefaultExceptionClassifier : ExceptionClassifier {

    override fun classify(exception: Throwable): CircuitBreakerClassification = when (exception) {
        // Business exceptions (4xx) - don't trigger circuit breaker
        is ClientBaseException -> CircuitBreakerClassification.IGNORE

        // System/infrastructure exceptions (5xx) - trigger circuit breaker
        is ServerBaseException -> CircuitBreakerClassification.RECORD

        // Unknown exceptions - use default circuit breaker behavior
        else -> CircuitBreakerClassification.DEFAULT
    }
}
