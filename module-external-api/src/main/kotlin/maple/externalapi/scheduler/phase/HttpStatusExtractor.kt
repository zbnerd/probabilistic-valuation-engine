package maple.externalapi.scheduler.phase

import java.util.concurrent.CompletionException
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClientResponseException

@Component
class HttpStatusExtractor {
    fun extract(ex: Throwable): Int {
        val cause = if (ex is CompletionException) ex.cause else ex
        return when (cause) {
            is WebClientResponseException -> cause.statusCode.value()
            else -> 0
        }
    }
}
