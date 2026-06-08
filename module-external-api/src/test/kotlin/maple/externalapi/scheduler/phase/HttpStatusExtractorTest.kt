package maple.externalapi.scheduler.phase

import java.util.concurrent.CompletionException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.WebClientResponseException

class HttpStatusExtractorTest {
    private val extractor = HttpStatusExtractor()

    @Test
    fun `extract returns status code from WebClientResponseException`() {
        val ex = WebClientResponseException.create(
            HttpStatus.NOT_FOUND.value(),
            "Not Found",
            org.springframework.http.HttpHeaders.EMPTY,
            byteArrayOf(),
            null,
        )

        assertEquals(404, extractor.extract(ex))
    }

    @Test
    fun `extract unwraps CompletionException and returns status from cause`() {
        val inner = WebClientResponseException.create(
            HttpStatus.INTERNAL_SERVER_ERROR.value(),
            "Server Error",
            org.springframework.http.HttpHeaders.EMPTY,
            byteArrayOf(),
            null,
        )
        val wrapped = CompletionException(inner)

        assertEquals(500, extractor.extract(wrapped))
    }

    @Test
    fun `extract returns 0 for non-WebClient exceptions`() {
        assertEquals(0, extractor.extract(RuntimeException("nope")))
    }
}
