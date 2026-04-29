package maple.expectation.infrastructure.monitoring.copilot.client

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.format.DateTimeFormatter
import maple.expectation.error.exception.InternalSystemException
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus

class PrometheusClient(
    private val httpClient: HttpClient,
    private val objectMapper: ObjectMapper,
    private val executor: LogicExecutor,
    private val prometheusUrl: String,
) {
    companion object {
        private const val TIMESTAMP_INDEX = 0
        private const val VALUE_INDEX = 1
        private const val ARRAY_SIZE = 2
        private val log = LoggerFactory.getLogger(PrometheusClient::class.java)
    }

    fun queryRange(promql: String, start: Instant, end: Instant, step: String): List<TimeSeries> = executor.execute(
        { queryRangeInternal(promql, start, end, step) },
        TaskContext.of("PrometheusClient", "QueryRange", promql),
    )

    private fun queryRangeInternal(promql: String, start: Instant, end: Instant, step: String): List<TimeSeries> {
        val url = buildQueryRangeUrl(promql, start, end, step)
        val request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build()
        val response = sendHttpRequest(request)

        if (response.statusCode() != HttpStatus.OK.value()) {
            throw InternalSystemException("Prometheus query failed: HTTP ${response.statusCode()} - ${response.body()}")
        }

        val prometheusResponse = parseResponse(response.body())

        if (prometheusResponse == null || prometheusResponse.data == null) {
            log.warn("Empty Prometheus response for query: {}", promql)
            return emptyList()
        }

        return prometheusResponse.data.result
    }

    private fun sendHttpRequest(request: HttpRequest): HttpResponse<String> = executor.executeWithTranslation(
        { httpClient.send(request, HttpResponse.BodyHandlers.ofString()) },
        { e, _ ->
            when (e) {
                is InterruptedException -> {
                    Thread.currentThread().interrupt()
                    InternalSystemException("Prometheus query interrupted", e)
                }
                is IOException -> InternalSystemException("Prometheus HTTP request failed: ${e.message}", e)
                else -> InternalSystemException("Prometheus request failed: ${e.message}", e)
            }
        },
        TaskContext.of("PrometheusClient", "SendRequest"),
    )

    private fun parseResponse(body: String): PrometheusResponse? = executor.executeWithTranslation(
        { objectMapper.readValue(body, PrometheusResponse::class.java) },
        { e, _ -> InternalSystemException("Failed to parse Prometheus response: ${e.message}", e) },
        TaskContext.of("PrometheusClient", "ParseResponse"),
    )

    private fun buildQueryRangeUrl(promql: String, start: Instant, end: Instant, step: String): String {
        val formatter = DateTimeFormatter.ISO_INSTANT
        return "$prometheusUrl/api/v1/query_range?query=${urlEncode(promql)}&start=${formatter.format(start)}&end=${formatter.format(end)}&step=$step"
    }

    private fun urlEncode(value: String): String = value
        .replace(" ", "+")
        .replace("\"", "%22")
        .replace("(", "%28")
        .replace(")", "%29")
        .replace("{", "%7B")
        .replace("}", "%7D")
        .replace("[", "%5B")
        .replace("]", "%5D")

    data class PrometheusResponse(
        val status: String,
        val data: QueryResponseData?,
    ) {
        companion object {
            @JsonCreator
            @JvmStatic
            fun create(
                @JsonProperty("status") status: String,
                @JsonProperty("data") data: QueryResponseData?,
            ): PrometheusResponse = PrometheusResponse(status, data)
        }
    }

    data class QueryResponseData(
        val resultType: String,
        val result: List<TimeSeries>,
    ) {
        companion object {
            @JsonCreator
            @JvmStatic
            fun create(
                @JsonProperty("resultType") resultType: String,
                @JsonProperty("result") result: List<TimeSeries>,
            ): QueryResponseData = QueryResponseData(resultType, result)
        }
    }

    data class TimeSeries(
        val metric: Map<String, String>,
        val values: List<ValuePoint>,
    ) {
        companion object {
            @JsonCreator
            @JvmStatic
            fun create(
                @JsonProperty("metric") metric: Map<String, String>,
                @JsonProperty("values") values: List<ValuePoint>,
            ): TimeSeries = TimeSeries(metric, values)
        }
    }

    @JsonDeserialize(using = ValuePointDeserializer::class)
    data class ValuePoint(
        val timestamp: Long,
        val value: String,
    ) {
        fun getValueAsDouble(): Double = value.toDoubleOrNull() ?: run {
            log.warn("Failed to parse value as double: {}", value)
            0.0
        }

        fun getTimestampAsInstant(): Instant = Instant.ofEpochSecond(timestamp)
    }

    class ValuePointDeserializer : JsonDeserializer<ValuePoint>() {
        @Throws(IOException::class)
        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): ValuePoint {
            val node = p.codec.readTree<JsonNode>(p)
            if (node.isArray && node.size() == ARRAY_SIZE) {
                val timestamp = node.get(TIMESTAMP_INDEX).asLong()
                val value = node.get(VALUE_INDEX).asText()
                return ValuePoint(timestamp, value)
            }
            throw IOException("Invalid ValuePoint format: expected [timestamp, value] array, got: $node")
        }
    }
}
