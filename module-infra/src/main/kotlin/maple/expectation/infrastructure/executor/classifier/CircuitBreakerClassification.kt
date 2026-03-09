@file:JvmName("CircuitBreakerClassification")

package maple.expectation.infrastructure.executor.classifier

/**
 * Circuit Breaker exception classification result.
 *
 * <p>Defines how exceptions should be handled by the circuit breaker pattern.
 * This enum separates exception classification logic from domain exceptions,
 * following the Strategy pattern similar to Spring's SQLExceptionTranslator.
 *
 * <h3>Classification Strategy:</h3>
 * <ul>
 *   <li><b>IGNORE</b>: Business exceptions (4xx) - should NOT trigger circuit breaker</li>
 *   <li><b>RECORD</b>: System exceptions (5xx) - should trigger circuit breaker</li>
 *   <li><b>DEFAULT</b>: Unknown exceptions - use default circuit breaker behavior</li>
 * </ul>
 *
 * @see ExceptionClassifier
 * @see maple.expectation.error.exception.base.ClientBaseException
 * @see maple.expectation.error.exception.base.ServerBaseException
 */
enum class CircuitBreakerClassification {
    /**
     * Exception should be ignored by circuit breaker.
     *
     * <p>Used for business/logic exceptions (4xx) where the failure is expected
     * and should not count toward circuit breaker failure threshold.
     *
     * <p>Examples: InvalidParameterException, CharacterNotFoundException
     */
    IGNORE,

    /**
     * Exception should be recorded by circuit breaker.
     *
     * <p>Used for system/infrastructure exceptions (5xx) where the failure
     * indicates a problem that should trigger circuit breaker protection.
     *
     * <p>Examples: ExternalServiceException, DatabaseConnectionException
     */
    RECORD,

    /**
     * Use default circuit breaker behavior.
     *
     * <p>For exceptions that don't fit clearly into IGNORE or RECORD categories,
     * let the circuit breaker use its default configuration.
     */
    DEFAULT,
}
