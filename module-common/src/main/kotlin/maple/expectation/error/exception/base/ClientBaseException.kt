@file:JvmName("ClientBaseException")

package maple.expectation.error.exception.base

import maple.expectation.error.ErrorCode

/**
 * Base class for client-side (4xx) business exceptions.
 *
 * <p>Represents errors caused by client requests (invalid input, missing resources,
 * permission violations, etc.). These exceptions:
 * <ul>
 *   <li>Return 4xx HTTP status codes</li>
 *   <li>Provide user-friendly error messages</li>
 *   <li>Are classified by ExceptionClassifier for circuit breaker handling</li>
 * </ul>
 *
 * <p><b>Note:</b> Circuit breaker classification is now handled by
 * {@link maple.expectation.infrastructure.executor.classifier.DefaultExceptionClassifier}
 * instead of marker interfaces, keeping domain exceptions pure.
 *
 * @see ErrorCode
 * @see maple.expectation.infrastructure.executor.classifier.DefaultExceptionClassifier
 */
abstract class ClientBaseException : BaseException {

    /**
     * Create exception with static error message.
     *
     * @param errorCode Error code enum (typically 4xx status)
     */
    constructor(errorCode: ErrorCode) : super(errorCode)

    /**
     * Create exception with dynamically formatted message.
     *
     * <p>Use this constructor when error message requires runtime values.
     *
     * <h3>Example:</h3>
     * <pre>
     * throw InvalidParameterException(
     *     errorCode = CommonErrorCode.INVALID_PARAMETER,
     *     args = arrayOf("level", -1)
     * )
     * // Message: "Invalid parameter 'level' value: -1"
     * </pre>
     *
     * @param errorCode Error code enum with format string
     * @param args Arguments to format into message
     */
    constructor(errorCode: ErrorCode, vararg args: Any?) : super(errorCode, *args)
}
