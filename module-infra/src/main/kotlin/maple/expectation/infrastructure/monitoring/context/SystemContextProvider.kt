package maple.expectation.infrastructure.monitoring.context

import maple.expectation.core.port.out.SystemMetricsPort
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.monitoring.collector.MetricCategory
import maple.expectation.infrastructure.monitoring.collector.MetricsCollectorStrategy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.EnumMap

/**
 * 시스템 컨텍스트 제공자 (Facade 패턴)
 *
 * <h3>Issue #251: AI SRE 모니터링</h3>
 *
 * <p>여러 MetricsCollectorStrategy를 통합하여 시스템 상태를 종합적으로 제공합니다.
 *
 * <h4>Facade 패턴 적용</h4>
 *
 * <ul>
 *   <li>복잡한 메트릭 수집 로직을 단순 인터페이스로 제공
 *   <li>AI SRE Analyzer에 필요한 컨텍스트 통합
 * </ul>
 *
 * <h4>CLAUDE.md 준수사항</h4>
 *
 * <ul>
 *   <li>Section 4 (SOLID): Facade로 복잡성 캡슐화
 *   <li>Section 6 (Design Patterns): Factory + Strategy 조합
 *   <li>Section 12 (LogicExecutor): 수집 실패 시 안전한 폴백
 * </ul>
 *
 * @see MetricsCollectorStrategy
 */
@Component
class SystemContextProvider(
    private val collectors: List<MetricsCollectorStrategy>,
    private val executor: LogicExecutor
) : SystemMetricsPort {

    companion object {
        private val log = LoggerFactory.getLogger(SystemContextProvider::class.java)
    }

    /**
     * 전체 시스템 컨텍스트 수집
     *
     * @return 카테고리별 메트릭 맵
     */
    override fun collectAllMetrics(): Map<MetricCategory, Map<String, Any>> {
        val result = EnumMap<MetricCategory, Map<String, Any>>(MetricCategory::class.java)

        // 우선순위 순으로 정렬하여 수집
        collectors.sortedBy { it.getOrder() }.forEach { collector ->
            collectSafely(collector, result)
        }

        return result
    }

    /**
     * 특정 카테고리 메트릭만 수집
     *
     * @param categories 수집할 카테고리들
     * @return 요청된 카테고리의 메트릭 맵
     */
    fun collectMetrics(vararg categories: MetricCategory): Map<MetricCategory, Map<String, Any>> {
        val result = EnumMap<MetricCategory, Map<String, Any>>(MetricCategory::class.java)

        for (category in categories) {
            collectors.filter { it.supports(category) }.firstOrNull()?.let { collector ->
                collectSafely(collector, result)
            }
        }

        return result
    }

    /**
     * AI 분석용 시스템 컨텍스트 문자열 생성
     *
     * @return AI 분석에 적합한 형식의 시스템 상태 문자열
     */
    fun buildContextForAi(): String {
        val allMetrics = collectAllMetrics()
        val sb = StringBuilder()

        sb.append("=== System Context at ${Instant.now()} ===\n\n")

        allMetrics.forEach { (category, metrics) ->
            sb.append("[${category.displayName}]\n")
            metrics.forEach { (key, value) ->
                // 중첩 맵 처리 (Circuit Breaker 상세 등)
                if (value is Map<*, *>) {
                    sb.append("  $key:\n")
                    @Suppress("UNCHECKED_CAST")
                    val nested = value as Map<String, Any>
                    nested.forEach { (k, v) -> sb.append("    $k: $v\n") }
                } else {
                    sb.append("  $key: $value\n")
                }
            }
            sb.append("\n")
        }

        return sb.toString()
    }

    /**
     * 핵심 지표 요약 생성 (Discord 알림용)
     *
     * @return 핵심 지표 요약 문자열
     */
    fun buildSummary(): String {
        val metrics = collectMetrics(MetricCategory.GOLDEN_SIGNALS, MetricCategory.CIRCUIT_BREAKER)

        val summary = linkedMapOf<String, Any>()

        // Golden Signals 요약
        metrics.getOrDefault(MetricCategory.GOLDEN_SIGNALS, emptyMap()).forEach { (key, value) ->
            if (key.contains("latency_p95") || key.contains("error_rate") || key.contains("saturation")) {
                summary[key] = value
            }
        }

        // Circuit Breaker 요약
        val cbMetrics = metrics.getOrDefault(MetricCategory.CIRCUIT_BREAKER, emptyMap())
        summary["cb_open_count"] = cbMetrics.getOrDefault("summary_open_count", 0L)

        return summary.entries.joinToString(" | ") { "${it.key}: ${it.value}" }
    }

    /** 안전하게 메트릭 수집 (실패 시 빈 맵 반환) */
    private fun collectSafely(
        collector: MetricsCollectorStrategy,
        result: MutableMap<MetricCategory, Map<String, Any>>
    ) {
        val context = TaskContext.of("Monitoring", "Collect", collector.getCategoryName())

        val metrics = executor.executeOrDefault(
            { collector.collect() },
            mapOf("error" to "Collection failed"),
            context
        )

        // 해당 카테고리 찾기
        for (category in MetricCategory.values()) {
            if (collector.supports(category)) {
                result[category] = metrics
                break
            }
        }
    }
}
