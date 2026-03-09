package maple.expectation.infrastructure.monitoring.prometheus

import java.time.Instant
import maple.expectation.core.port.out.MetricsQueryPort
import maple.expectation.infrastructure.monitoring.copilot.client.PrometheusClient
import org.springframework.stereotype.Component

/**
 * MetricsQueryPort 구현체 (ADR-005)
 *
 * <p>PrometheusClient를 래핑하여 Port 인터페이스 구현
 *
 * @param prometheusClient Prometheus HTTP 클라이언트
 */
@Component
class MetricsQueryPortAdapter(
    private val prometheusClient: PrometheusClient,
) : MetricsQueryPort {

    override fun queryRange(
        promql: String,
        start: Instant,
        end: Instant,
        step: String,
    ): List<MetricsQueryPort.MetricTimeSeries> {
        val prometheusSeries = prometheusClient.queryRange(promql, start, end, step)

        return prometheusSeries.map { series ->
            MetricsQueryPort.MetricTimeSeries(
                metric = series.metric,
                values = series.values.map { vp ->
                    MetricsQueryPort.MetricValuePoint(
                        timestamp = vp.timestamp,
                        value = vp.value,
                    )
                },
            )
        }
    }
}
