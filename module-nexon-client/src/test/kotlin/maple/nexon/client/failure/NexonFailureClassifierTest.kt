package maple.nexon.client.failure

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.netty.channel.ConnectTimeoutException
import io.netty.handler.timeout.ReadTimeoutException
import java.time.Duration
import java.util.concurrent.TimeoutException as FutureTimeoutException
import maple.nexon.client.model.NexonEndpointPurpose
import maple.nexon.client.model.NexonRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NexonFailureClassifierTest {
    private val classifier = NexonFailureClassifier(jacksonObjectMapper())
    private val characterList = request(NexonEndpointPurpose.CHARACTER_LIST, "/maplestory/v1/character/list")
    private val ocidLookup = request(NexonEndpointPurpose.OCID_LOOKUP, "/maplestory/v1/id")

    @Test
    fun `classifies endpoint-aware status matrix without retaining body`() {
        assertThat(classifier.classifyHttp(characterList, 401, error("OPENAPI00001", "bad credential"), null))
            .isInstanceOf(InvalidCredential::class.java)
        assertThat(classifier.classifyHttp(characterList, 403, error("OPENAPI00002", "forbidden"), null))
            .isInstanceOf(InvalidCredential::class.java)
        assertThat(classifier.classifyHttp(ocidLookup, 400, error("OPENAPI00004", "Data not found"), null))
            .isInstanceOf(NotFound::class.java)
        assertThat(classifier.classifyHttp(characterList, 400, error("OPENAPI00004", "Data not found"), null))
            .isInstanceOf(NotFound::class.java)
        assertThat(classifier.classifyHttp(characterList, 400, error("OPENAPI99999", "invalid field"), null))
            .isInstanceOf(InvalidRequest::class.java)
        assertThat(classifier.classifyHttp(characterList, 429, error("OPENAPI00007", "slow down"), Duration.ofSeconds(7)))
            .isInstanceOf(RateLimited::class.java)
            .extracting("retryAfter")
            .isEqualTo(Duration.ofSeconds(7))
        assertThat(classifier.classifyHttp(characterList, 503, error("UPSTREAM", "unavailable"), null))
            .isInstanceOf(UpstreamUnavailable::class.java)
    }

    @Test
    fun `classifies timeout body limit and decode failures`() {
        assertThat(classifier.classifyTransport(characterList, ConnectTimeoutException("connect")))
            .isInstanceOf(Timeout::class.java)
            .extracting("kind")
            .isEqualTo(TimeoutKind.CONNECT)
        assertThat(classifier.classifyTransport(characterList, ReadTimeoutException.INSTANCE))
            .isInstanceOf(Timeout::class.java)
            .extracting("kind")
            .isEqualTo(TimeoutKind.RESPONSE)
        assertThat(classifier.classifyTransport(characterList, FutureTimeoutException("call")))
            .isInstanceOf(Timeout::class.java)
            .extracting("kind")
            .isEqualTo(TimeoutKind.CALL)
        assertThat(classifier.responseTooLarge(characterList)).isInstanceOf(ResponseTooLarge::class.java)
        assertThat(classifier.decodeFailure(characterList)).isInstanceOf(DecodeFailure::class.java)
    }

    @Test
    fun `failure strings properties and cause chains contain no credential query or raw body`() {
        val failure = classifier.classifyHttp(
            characterList,
            400,
            error("unsafe code with $SECRET", RAW_BODY),
            null,
        )

        val rendered = generateSequence<Throwable>(failure) { it.cause }
            .joinToString("\n") { throwable -> throwable.toString() + throwable.stackTraceToString() }
        assertThat(rendered).doesNotContain(SECRET, RAW_BODY, "UnsafeCharacterName")
        assertThat(failure.cause).isNull()
        assertThat(failure.nexonCode).isNull()
        assertThat(failure.endpointTemplate).isEqualTo("/maplestory/v1/character/list")
    }

    private fun request(purpose: NexonEndpointPurpose, template: String): NexonRequest = NexonRequest(
        purpose = purpose,
        path = template,
        query = mapOf("character_name" to "UnsafeCharacterName"),
        endpointTemplate = template,
    )

    private fun error(code: String, message: String): ByteArray = """{"error":{"name":"$code","message":"$message"}}""".toByteArray()

    private companion object {
        private const val SECRET = "synthetic-byok-secret-should-never-escape"
        private const val RAW_BODY = "raw-sensitive-response-body"
    }
}
