@file:JvmName("ExceptionClassifier")

package maple.expectation.infrastructure.executor.classifier

/**
 * Strategy interface for classifying exceptions for circuit breaker handling.
 *
 * <p>This interface follows the Strategy pattern, similar to Spring's
 * SQLExceptionTranslator, to decouple exception classification logic from
 * domain exception classes.
 *
 * <h3>Design Rationale:</h3>
 * <ul>
 *   <li><b>Domain Purity</b>: Domain exceptions remain pure without infrastructure dependencies</li>
 *   <li><b>Centralized Policy</b>: Classification logic in one place for easy maintenance</li>
 *   <li><b>Extensibility</b>: Custom classifiers can be injected for specific use cases</li>
 * </ul>
 *
 * <h3>Lambda Boundary Pattern:</h3>
 * <p>When using LogicExecutor, the classifier operates at the infrastructure boundary:
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────┐
 * │                     LogicExecutor (Infrastructure)               │
 * │  ┌───────────────────────────────────────────────────────────┐  │
 * │  │                 Lambda Boundary                          │  │
 * │  │  • External API calls                                    │  │
 * │  │  • DB queries                                            │  │
 * │  │  • Exception classification (ExceptionClassifier)        │  │
 * │  └───────────────────────────────────────────────────────────┘  │
 * │                              ↓                                   │
 * │  ┌───────────────────────────────────────────────────────────┐  │
 * │  │           Outside Lambda (Business)                       │  │
 * │  │  • Pure calculations                                      │  │
 * │  │  • Domain service processing                              │  │
 * │  │  • Value Object functions                                 │  │
 * │  └───────────────────────────────────────────────────────────┘  │
 * └─────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>Usage Example:</h3>
 * <pre>
 * // Good: Data fetch inside lambda, calculation outside
 * val data = executor.execute({ repository.findById(id) }, context)
 * val result = domainService.calculate(data)  // Outside lambda - pure function
 *
 * // Bad: Everything inside lambda (unclear responsibility)
 * val result = executor.execute({
 *     val data = repository.findById(id)
 *     domainService.calculate(data)  // Business logic in infrastructure wrapper
 * }, context)
 * </pre>
 *
 * @see CircuitBreakerClassification
 * @see DefaultExceptionClassifier
 */
fun interface ExceptionClassifier {

    /**
     * Classify an exception for circuit breaker handling.
     *
     * @param exception The exception to classify
     * @return The classification result indicating how circuit breaker should handle it
     */
    fun classify(exception: Throwable): CircuitBreakerClassification
}
