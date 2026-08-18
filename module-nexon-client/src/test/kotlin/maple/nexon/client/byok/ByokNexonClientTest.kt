package maple.nexon.client.byok

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import maple.nexon.client.config.ByokNexonClientProperties
import maple.nexon.client.config.NexonClientProfile
import maple.nexon.client.failure.DecodeFailure
import maple.nexon.client.failure.InvalidCredential
import maple.nexon.client.failure.InvalidRequest
import maple.nexon.client.failure.NexonFailureClassifier
import maple.nexon.client.failure.NotFound
import maple.nexon.client.failure.RateLimited
import maple.nexon.client.failure.ResponseTooLarge
import maple.nexon.client.failure.Timeout
import maple.nexon.client.failure.TimeoutKind
import maple.nexon.client.failure.UpstreamUnavailable
import maple.nexon.client.metrics.NexonClientMetrics
import maple.nexon.client.transport.NexonTransport
import maple.nexon.client.transport.NexonTransportFactory
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ByokNexonClientTest {
    private lateinit var server: HttpServer
    private lateinit var transport: NexonTransport
    private lateinit var client: ByokNexonClient
    private val scenario = AtomicReference<ResponseScenario>()
    private val timeoutEntered = CountDownLatch(1)
    private val releaseTimeout = CountDownLatch(1)

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.executor = Executors.newVirtualThreadPerTaskExecutor()
        server.createContext("/maplestory/v1/character/list", ::handle)
        server.start()
        val classifier = NexonFailureClassifier(jacksonObjectMapper())
        transport = NexonTransportFactory(
            classifier = classifier,
            metrics = NexonClientMetrics(SimpleMeterRegistry()),
            baseUrl = "http://${server.address.hostString}:${server.address.port}",
        ).create(
            NexonClientProfile.USER_BYOK,
            ByokNexonClientProperties(callTimeoutSeconds = 3, metricsEnabled = false),
        )
        client = ByokNexonClient(transport, CharacterListDecoder(jacksonObjectMapper()))
    }

    @AfterEach
    fun tearDown() {
        releaseTimeout.countDown()
        val disposed = transport.provider.disposeLater().toFuture()
        await().until(disposed::isDone)
        server.stop(0)
        (server.executor as? AutoCloseable)?.close()
    }

    @Test
    fun `decodes populated empty and explicit null lists`() {
        respond(
            200,
            """{"account_list":[{"account_id":"account-1","character_list":[{"ocid":"ocid-1","character_name":"Hero","world_name":"Scania","character_class":"Warrior","character_level":280}]}]}""",
        )
        val populated = success()
        assertThat(populated.accounts.single().accountId).isEqualTo("account-1")
        assertThat(populated.characters.single()).isEqualTo(
            NexonCharacter("ocid-1", "Hero", "Scania", "Warrior", 280),
        )

        respond(200, """{"account_list":[]}""")
        assertThat(success().accounts).isEmpty()
        respond(200, """{"account_list":[{"account_id":null,"character_list":null}]}""")
        val explicitNulls = success()
        assertThat(explicitNulls.accounts.single().accountId).isNull()
        assertThat(explicitNulls.characters).isEmpty()
    }

    @Test
    fun `preserves endpoint-aware credential absence and transient failures`() {
        respond(401, error("OPENAPI00001", "credential rejected"))
        assertThat(failure()).isInstanceOf(InvalidCredential::class.java)
        respond(400, error("OPENAPI00004", "Data not found"))
        assertThat(failure()).isInstanceOf(NotFound::class.java)
        respond(400, error("OPENAPI99999", "bad request"))
        assertThat(failure()).isInstanceOf(InvalidRequest::class.java)
        respond(429, error("OPENAPI00007", "slow down"), mapOf("Retry-After" to "9"))
        val rateLimited = failure()
        assertThat(rateLimited).isInstanceOf(RateLimited::class.java)
        assertThat((rateLimited as? RateLimited)?.retryAfter).isEqualTo(Duration.ofSeconds(9))
        respond(503, error("UPSTREAM", "unavailable"))
        assertThat(failure()).isInstanceOf(UpstreamUnavailable::class.java)
    }

    @Test
    fun `classifies body cap malformed success and call timeout`() {
        scenario.set(ResponseScenario(200, ByteArray(256 * 1024 + 1) { 1 }))
        assertThat(failure()).isInstanceOf(ResponseTooLarge::class.java)
        respond(200, "{malformed")
        assertThat(failure()).isInstanceOf(DecodeFailure::class.java)

        scenario.set(ResponseScenario(timeout = true))
        val completion = client.getCharacterList(KEY)
        await().until { timeoutEntered.count == 0L }
        await().until(completion::isDone)
        val timeout = unwrap(completion.exceptionNow())
        assertThat(timeout).isInstanceOf(Timeout::class.java)
        assertThat((timeout as? Timeout)?.kind).isEqualTo(TimeoutKind.CALL)
    }

    private fun success(): NexonCharacterList {
        val completion = client.getCharacterList(KEY)
        await().until(completion::isDone)
        return completion.resultNow()
    }

    private fun failure(): Throwable {
        val completion = client.getCharacterList(KEY)
        await().until(completion::isDone)
        assertThat(completion).isCompletedExceptionally
        return unwrap(completion.exceptionNow())
    }

    private fun unwrap(failure: Throwable): Throwable = if (failure is CompletionException && failure.cause != null) requireNotNull(failure.cause) else failure

    private fun respond(status: Int, body: String, headers: Map<String, String> = emptyMap()) {
        scenario.set(ResponseScenario(status, body.toByteArray(), headers))
    }

    private fun handle(exchange: HttpExchange) {
        val response = requireNotNull(scenario.get())
        if (response.timeout) {
            timeoutEntered.countDown()
            releaseTimeout.await()
        }
        response.headers.forEach(exchange.responseHeaders::add)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(response.status, response.body.size.toLong())
        exchange.responseBody.use { it.write(response.body) }
    }

    private fun error(code: String, message: String): String = """{"error":{"name":"$code","message":"$message"}}"""

    private data class ResponseScenario(
        val status: Int = 200,
        val body: ByteArray = "{}".toByteArray(),
        val headers: Map<String, String> = emptyMap(),
        val timeout: Boolean = false,
    )

    private companion object {
        private const val KEY = "synthetic-byok-key"
    }
}
