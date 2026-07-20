package maple.nexon.client.transport

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import maple.nexon.client.config.ByokNexonClientProperties
import maple.nexon.client.config.NexonClientProfile
import maple.nexon.client.config.SystemNexonClientProperties
import maple.nexon.client.failure.NexonFailureClassifier
import maple.nexon.client.failure.ResponseTooLarge
import maple.nexon.client.metrics.NexonClientMetrics
import maple.nexon.client.model.NexonEndpointPurpose
import maple.nexon.client.model.NexonRequest
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.util.DefaultUriBuilderFactory

class NexonTransportTest {
    private lateinit var server: HttpServer
    private val requestCapture = AtomicReference<RequestCapture>()
    private val systemEntered = CountDownLatch(1)
    private val releaseSystem = CountDownLatch(1)

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.executor = Executors.newVirtualThreadPerTaskExecutor()
        server.createContext("/", ::handle)
        server.start()
    }

    @AfterEach
    fun stopServer() {
        releaseSystem.countDown()
        server.stop(0)
        (server.executor as? AutoCloseable)?.close()
    }

    @Test
    fun `encodes Korean query and sends exactly one credential and accept header`() {
        val transport = transport(NexonClientProfile.SYSTEM_BULK, SystemNexonClientProperties(metricsEnabled = false))
        val request = NexonRequest(
            purpose = NexonEndpointPurpose.OCID_LOOKUP,
            path = "/maplestory/v1/id",
            query = mapOf("character_name" to "진격캐넌"),
            endpointTemplate = "/maplestory/v1/id",
        )
        val uriFactory = DefaultUriBuilderFactory("http://${server.address.hostString}:${server.address.port}").apply {
            encodingMode = DefaultUriBuilderFactory.EncodingMode.VALUES_ONLY
        }
        assertThat(
            uriFactory.builder().path(request.path)
                .queryParam("character_name", "{characterName}")
                .build(mapOf("characterName" to "진격캐넌"))
                .rawQuery,
        ).isEqualTo("character_name=%EC%A7%84%EA%B2%A9%EC%BA%90%EB%84%8C")
        val response = transport.exchange(request, "synthetic-system-key")
        await().until(response::isDone)

        assertThat(response.resultNow()).containsExactly(*RESPONSE)
        assertThat(requestCapture.get().rawQuery)
            .isEqualTo("character_name=%EC%A7%84%EA%B2%A9%EC%BA%90%EB%84%8C")
        assertThat(requestCapture.get().apiKeys).containsExactly("synthetic-system-key")
        assertThat(requestCapture.get().accept).containsExactly("application/json")
        transport.provider.disposeLater().subscribe()
    }

    @Test
    fun `profile body cap becomes typed response-too-large failure`() {
        val transport = transport(
            NexonClientProfile.USER_BYOK,
            ByokNexonClientProperties(maxInMemorySizeBytes = 1_024, metricsEnabled = false),
        )
        val request = request("/too-large", NexonEndpointPurpose.CHARACTER_LIST)

        val response = transport.exchange(request, "synthetic-byok-key")
        await().until(response::isDone)

        assertThat(response).isCompletedExceptionally
        assertThat(response.exceptionNow()).isInstanceOf(ResponseTooLarge::class.java)
        transport.provider.disposeLater().subscribe()
    }

    @Test
    fun `system saturation does not consume BYOK provider capacity`() {
        val system = transport(
            NexonClientProfile.SYSTEM_BULK,
            SystemNexonClientProperties(maxConnections = 1, pendingAcquireMaxCount = 1, metricsEnabled = false),
        )
        val byok = transport(
            NexonClientProfile.USER_BYOK,
            ByokNexonClientProperties(maxConnections = 1, pendingAcquireMaxCount = 1, metricsEnabled = false),
        )

        val held = system.exchange(request("/hold", NexonEndpointPurpose.OCID_LOOKUP), "system-key")
        await().until { systemEntered.count == 0L }
        val independent = byok.exchange(request("/fast", NexonEndpointPurpose.CHARACTER_LIST), "byok-key")
        await().until(independent::isDone)

        assertThat(independent.resultNow()).isEqualTo(RESPONSE)
        assertThat(held).isNotDone()
        assertThat(system.provider).isNotSameAs(byok.provider)
        releaseSystem.countDown()
        await().until(held::isDone)
        system.provider.disposeLater().subscribe()
        byok.provider.disposeLater().subscribe()
    }

    @Test
    fun `metric URI strips query identifiers`() {
        val factory = factory()

        assertThat(factory.normalizeMetricUri("/maplestory/v1/id?character_name=SensitiveName"))
            .isEqualTo("/maplestory/v1/id")
    }

    private fun transport(profile: NexonClientProfile, properties: Any): NexonTransport = when (properties) {
        is SystemNexonClientProperties -> factory().create(profile, properties)
        is ByokNexonClientProperties -> factory().create(profile, properties)
        else -> error("unsupported test properties")
    }

    private fun factory(): NexonTransportFactory = NexonTransportFactory(
        classifier = NexonFailureClassifier(jacksonObjectMapper()),
        metrics = NexonClientMetrics(SimpleMeterRegistry()),
        baseUrl = "http://${server.address.hostString}:${server.address.port}",
    )

    private fun request(path: String, purpose: NexonEndpointPurpose): NexonRequest = NexonRequest(
        purpose = purpose,
        path = path,
        query = emptyMap(),
        endpointTemplate = path,
    )

    private fun handle(exchange: HttpExchange) {
        requestCapture.set(
            RequestCapture(
                rawQuery = exchange.requestURI.rawQuery,
                apiKeys = exchange.requestHeaders["x-nxopen-api-key"].orEmpty(),
                accept = exchange.requestHeaders["Accept"].orEmpty(),
            ),
        )
        if (exchange.requestURI.path == "/hold") {
            systemEntered.countDown()
            releaseSystem.await()
        }
        val body = if (exchange.requestURI.path == "/too-large") ByteArray(2_048) { 1 } else RESPONSE
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private data class RequestCapture(
        val rawQuery: String?,
        val apiKeys: List<String>,
        val accept: List<String>,
    )

    private companion object {
        private val RESPONSE = "{\"ok\":true}".toByteArray(StandardCharsets.UTF_8)
    }
}
