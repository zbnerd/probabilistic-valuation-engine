package maple.nexon.client.security

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.lang.reflect.Modifier
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CompletionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import maple.nexon.client.byok.ByokNexonClient
import maple.nexon.client.byok.CharacterListDecoder
import maple.nexon.client.config.ByokNexonClientProperties
import maple.nexon.client.config.NexonClientProfile
import maple.nexon.client.failure.DecodeFailure
import maple.nexon.client.failure.InvalidCredential
import maple.nexon.client.failure.InvalidRequest
import maple.nexon.client.failure.NexonFailure
import maple.nexon.client.failure.NexonFailureClassifier
import maple.nexon.client.failure.NotFound
import maple.nexon.client.failure.RateLimited
import maple.nexon.client.failure.ResponseTooLarge
import maple.nexon.client.failure.Timeout
import maple.nexon.client.failure.TimeoutKind
import maple.nexon.client.failure.UpstreamUnavailable
import maple.nexon.client.metrics.NexonClientMetrics
import maple.nexon.client.model.NexonEndpointPurpose
import maple.nexon.client.model.NexonRequest
import maple.nexon.client.transport.NexonTransport
import maple.nexon.client.transport.NexonTransportFactory
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class NexonCredentialRedactionTest {
    private lateinit var server: HttpServer
    private lateinit var transport: NexonTransport
    private lateinit var client: ByokNexonClient
    private lateinit var registry: SimpleMeterRegistry
    private lateinit var logger: Logger
    private lateinit var appender: ListAppender<ILoggingEvent>
    private val response = AtomicReference("""{"account_list":[]}""")
    private val receivedKeys = AtomicReference<List<String>>(emptyList())

    @BeforeEach
    fun setUp() {
        logger = requireNotNull(LoggerFactory.getLogger("maple.nexon.client") as? Logger)
        appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.executor = Executors.newVirtualThreadPerTaskExecutor()
        server.createContext("/maplestory/v1/character/list", ::handle)
        server.start()
        registry = SimpleMeterRegistry()
        val mapper = jacksonObjectMapper()
        transport = NexonTransportFactory(
            NexonFailureClassifier(mapper),
            NexonClientMetrics(registry),
            "http://${server.address.hostString}:${server.address.port}",
        ).create(
            NexonClientProfile.USER_BYOK,
            ByokNexonClientProperties(metricsEnabled = false),
        )
        client = ByokNexonClient(transport, CharacterListDecoder(mapper))
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(appender)
        appender.stop()
        val disposed = transport.provider.disposeLater().toFuture()
        await().until(disposed::isDone)
        server.stop(0)
        (server.executor as? AutoCloseable)?.close()
        registry.close()
    }

    @Test
    fun `credential has request lexical lifetime and never enters object graph logs metrics or failures`() {
        val success = client.getCharacterList(SECRET)
        await().until(success::isDone)
        val model = success.resultNow()
        assertThat(receivedKeys.get()).containsExactly(SECRET)
        assertRedacted(renderGraph(client, transport, model))

        response.set("""{"error":{"name":"OPENAPI99999","message":"$RAW_BODY $SECRET"}}""")
        val failed = client.getCharacterList(SECRET)
        await().until(failed::isDone)
        val failure = unwrap(failed.exceptionNow())
        assertRedacted(renderFailure(failure))
        assertRedacted(renderGraph(client, transport, failure))
        assertRedacted(appender.list.joinToString("\n", transform = ILoggingEvent::getFormattedMessage))
        assertRedacted(registry.meters.joinToString("\n") { it.id.toString() })

        everyFailure().forEach { typedFailure ->
            assertRedacted(renderFailure(typedFailure))
            assertRedacted(renderGraph(client, transport, typedFailure))
        }
    }

    private fun everyFailure(): List<NexonFailure> {
        val request = NexonRequest(
            NexonEndpointPurpose.CHARACTER_LIST,
            "/maplestory/v1/character/list",
            emptyMap(),
            "/maplestory/v1/character/list",
        )
        return listOf(
            InvalidCredential(request, 401, "OPENAPI00001"),
            NotFound(request, 400, "OPENAPI00004"),
            InvalidRequest(request, 400, "OPENAPI99999"),
            RateLimited(request, 429, "OPENAPI00007", Duration.ofSeconds(1)),
            Timeout(request, TimeoutKind.CALL),
            UpstreamUnavailable(request, 503, "UPSTREAM"),
            ResponseTooLarge(request),
            DecodeFailure(request),
        )
    }

    private fun renderFailure(failure: Throwable): String = generateSequence(failure) { it.cause }
        .joinToString("\n") { it.toString() + it.stackTraceToString() }

    private fun renderGraph(vararg roots: Any?): String {
        val queue = ArrayDeque<Any>()
        roots.filterNotNull().forEach(queue::add)
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val rendered = StringBuilder()
        while (queue.isNotEmpty()) {
            val value = queue.removeFirst()
            if (!seen.add(value)) continue
            when (value) {
                is String -> rendered.append(value).append('\n')
                is Iterable<*> -> value.filterNotNull().forEach(queue::add)
                is Map<*, *> -> value.entries.forEach { entry ->
                    entry.key?.let(queue::add)
                    entry.value?.let(queue::add)
                }
                else -> if (value.javaClass.packageName.startsWith("maple.nexon.client")) {
                    value.javaClass.declaredFields
                        .filterNot { Modifier.isStatic(it.modifiers) }
                        .mapNotNull { field ->
                            runCatching {
                                if (field.trySetAccessible()) field.get(value) else null
                            }.getOrNull()
                        }
                        .forEach(queue::add)
                }
            }
        }
        return rendered.toString()
    }

    private fun assertRedacted(rendered: String) {
        assertThat(rendered).doesNotContain(SECRET, RAW_BODY)
    }

    private fun unwrap(failure: Throwable): Throwable = if (failure is CompletionException && failure.cause != null) requireNotNull(failure.cause) else failure

    private fun handle(exchange: HttpExchange) {
        receivedKeys.set(exchange.requestHeaders["x-nxopen-api-key"].orEmpty())
        val body = response.get().toByteArray()
        val status = if (response.get().contains("OPENAPI99999")) 400 else 200
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private companion object {
        private const val SECRET = "synthetic-byok-secret-exact-value"
        private const val RAW_BODY = "raw-sensitive-body-marker"
    }
}
