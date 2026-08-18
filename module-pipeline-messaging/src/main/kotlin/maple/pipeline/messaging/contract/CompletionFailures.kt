package maple.pipeline.messaging.contract

import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

object CompletionFailures {
    fun unwrap(failure: Throwable): Throwable = when (failure) {
        is CompletionException -> failure.cause?.let(::unwrap) ?: failure
        is ExecutionException -> failure.cause?.let(::unwrap) ?: failure
        else -> failure
    }
}
