package maple.expectation.core.calculation.error

import maple.expectation.core.calculation.probability.ProbabilityKey
import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ServerBaseException

open class ValuationInvariantException : ServerBaseException {
    constructor(message: String) : super(CommonErrorCode.DATA_PROCESSING_ERROR, message)

    constructor(message: String, cause: Throwable) : super(
        CommonErrorCode.DATA_PROCESSING_ERROR,
        cause,
        message,
    )
}

class MissingProbabilityException(
    val key: ProbabilityKey,
) : ValuationInvariantException("Missing probability rows for key=$key")

open class ProbabilityTableInitializationException : ServerBaseException {
    constructor(message: String) : super(CommonErrorCode.DATA_INITIALIZATION_FAILED, message)

    constructor(message: String, cause: Throwable) : super(
        CommonErrorCode.DATA_INITIALIZATION_FAILED,
        cause,
        message,
    )
}
