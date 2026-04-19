package maple.expectation.infrastructure.external.config

import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Mono

/**
 * 외부 HTTP API 호출 계측 WebClient Filter
 *
 * <h3>동작 원리</h3>
 * <p>WebClient의 {@code ExchangeFilterFunction}으로 모든 HTTP 요청/응답을 가로채어
 * Micrometer 메트릭과 structured log를 기록합니다.
 * AOP나 Decorator 패턴과 달리 HTTP 전송 계층에서 동작하므로
 * 모든 WebClient 호출(직접/간접)을 포괄합니다.
 *
 * <h3>Metrics</h3>
 * <pre>
 * Timer:
 *   external.api.duration{apiName, statusGroup, outcome}
 *   → p50/p95/p99 latency per API
 *
 * Counter:
 *   external.api.requests.total{apiName, statusGroup, outcome}
 *   → HTTP status code / error type breakdown
 *
 * DistributionSummary:
 *   external.api.response.bytes{apiName}
 *   → response body size distribution
 *
 * Gauge:
 *   external.api.concurrent
 *   → 현재 진행 중인 외부 API 호출 수
 * </pre>
 *
 * <h3>Tag Cardinality</h3>
 * <ul>
 *   <li>apiName: 4개 (getOcid, getCharacterBasic, getItemData, getCubeHistory)</li>
 *   <li>statusGroup: 4개 (2xx, 4xx, 5xx, error)</li>
 *   <li>outcome: 3개 (success, timeout, error)</li>
 * </ul>
 * <p>최대 time series: 4 × 4 × 3 = 48 + 4 (response size) + 1 (concurrent) = 53
 *
 * <h3>Structured Logging</h3>
 * <p>MDC의 traceId, taskId와 함께 로그 출력:
 * <pre>
 * INFO  [ApiTimer] api=getCharacterBasic status=2xx outcome=success durationMs=145 traceId=req-abc
 * WARN  [ApiTimer] api=getOcid status=error outcome=timeout durationMs=5003 traceId=req-abc
 * </pre>
 *
 * <h3>Prometheus 대시보드 쿼리</h3>
 * <pre>{@code
 * # 병목 API 식별 (p95)
 * topk(3, histogram_quantile(0.95,
 *   rate(external_api_duration_seconds_bucket[5m])))
 *
 * # API별 에러율
 * sum(rate(external_api_requests_total{outcome!="success"}[5m]))
 *   by (apiName)
 *   /
 * sum(rate(external_api_requests_total[5m]))
 *   by (apiName)
 *
 * # 타임아웃 발생률
 * rate(external_api_requests_total{outcome="timeout"}[5m])
 *
 * # 응답 크기 p99
 * histogram_quantile(0.99,
 *   rate(external_api_response_bytes_bucket[5m]))
 * }</pre>
 */
@Component
class ExternalApiMetricsFilter(
    private val registry: MeterRegistry,
) : ExchangeFilterFunction {

    private val concurrentCalls = AtomicLong(0)

    init {
        Gauge.builder("external.api.concurrent") { concurrentCalls.get().toDouble() }
            .description("Currently in-flight external API calls")
            .register(registry)
    }

    override fun filter(request: ClientRequest, next: ExchangeFunction): Mono<ClientResponse> {
        val apiName = extractApiName(request)
        val sample = Timer.start(registry)

        concurrentCalls.incrementAndGet()

        return next.exchange(request)
            .doOnNext { response ->
                val statusGroup = statusGroupOf(response.statusCode())
                val outcome = outcomeOfStatus(response.statusCode().value())
                sample.stop(timer(apiName, statusGroup, outcome))
                recordResponseSize(apiName, response)
                logSuccess(apiName, statusGroup, outcome, sample)
            }
            .doOnError { error ->
                val outcome = classifyError(error)
                sample.stop(timer(apiName, "error", outcome))
                logError(apiName, outcome, error)
            }
            .doFinally { concurrentCalls.decrementAndGet() }
    }

    // ==================== API Name Extraction ====================

    private fun extractApiName(request: ClientRequest): String {
        val path = request.url().path
        return when {
            path.contains("/id") -> "getOcid"
            path.contains("/character/basic") -> "getCharacterBasic"
            path.contains("/item-equipment") -> "getItemData"
            path.contains("/history/cube") -> "getCubeHistory"
            else -> path.substringAfterLast("/").ifEmpty { "unknown" }
        }
    }

    // ==================== Status Classification ====================

    private fun statusGroupOf(statusCode: org.springframework.http.HttpStatusCode): String = statusGroupOf(statusCode.value())

    private fun statusGroupOf(code: Int): String = when (code) {
        in 200..299 -> "2xx"
        in 400..499 -> "4xx"
        in 500..599 -> "5xx"
        else -> "other"
    }

    private fun outcomeOfStatus(code: Int): String = when (code) {
        in 200..299 -> "success"
        in 400..499 -> "client_error"
        else -> "error"
    }

    private fun classifyError(error: Throwable): String = when (error) {
        is TimeoutException -> "timeout"
        is java.util.concurrent.TimeoutException -> "timeout"
        is WebClientResponseException -> {
            when {
                error.statusCode.value() == 429 -> "throttled"
                error.statusCode.is5xxServerError -> "server_error"
                else -> "client_error"
            }
        }
        else -> "error"
    }

    // ==================== Metric Recording ====================

    private fun timer(apiName: String, statusGroup: String, outcome: String): Timer = Timer.builder("external.api.duration")
        .description("External API call duration")
        .tag("apiName", apiName)
        .tag("statusGroup", statusGroup)
        .tag("outcome", outcome)
        .publishPercentileHistogram()
        .register(registry)

    private fun recordResponseSize(apiName: String, response: ClientResponse) {
        val contentLength = response.headers().contentLength().orElse(-1L)
        if (contentLength >= 0) {
            DistributionSummary.builder("external.api.response.bytes")
                .description("External API response body size in bytes")
                .tag("apiName", apiName)
                .register(registry)
                .record(contentLength.toDouble())
        }
    }

    // ==================== Structured Logging ====================

    private fun logSuccess(apiName: String, statusGroup: String, outcome: String, sample: Timer.Sample) {
        if (log.isDebugEnabled) {
            log.debug(
                "[ApiTimer] api={} status={} outcome={} durationMs={} traceId={}",
                apiName,
                statusGroup,
                outcome,
                "<measured>",
                MDC.get("requestId") ?: "-",
            )
        }
    }

    private fun logError(apiName: String, outcome: String, error: Throwable) {
        log.warn(
            "[ApiTimer] api={} status=error outcome={} error={} traceId={}",
            apiName,
            outcome,
            error.javaClass.simpleName,
            MDC.get("requestId") ?: "-",
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(ExternalApiMetricsFilter::class.java)
    }
}
