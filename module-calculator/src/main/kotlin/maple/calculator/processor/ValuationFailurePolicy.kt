package maple.calculator.processor

import maple.expectation.core.calculation.error.ProbabilityTableInitializationException
import maple.expectation.core.calculation.error.ValuationInvariantException
import maple.expectation.error.exception.InvalidPotentialGradeException
import maple.expectation.error.exception.OptionParseException
import maple.expectation.error.exception.UnsupportedCalculationEngineException
import org.springframework.stereotype.Component

sealed interface ItemFailureDecision {
    data class SourceError(val message: String) : ItemFailureDecision

    data class AbortChunk(val cause: Throwable) : ItemFailureDecision
}

@Component
class ValuationFailurePolicy {
    fun classify(failure: Throwable): ItemFailureDecision = when (failure) {
        is ValuationInvariantException,
        is ProbabilityTableInitializationException,
        -> ItemFailureDecision.AbortChunk(failure)

        is InvalidPotentialGradeException,
        is OptionParseException,
        is UnsupportedCalculationEngineException,
        -> ItemFailureDecision.SourceError(
            failure.message ?: failure.javaClass.simpleName,
        )

        else -> ItemFailureDecision.AbortChunk(failure)
    }
}
