package maple.expectation.infrastructure.batch

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import maple.expectation.core.domain.model.AlertMessage
import maple.expectation.core.port.out.AlertPort
import maple.expectation.core.port.out.SystemMetricsPort
import maple.expectation.infrastructure.lock.LockStrategy
import maple.expectation.infrastructure.monitoring.collector.MetricCategory
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 모니터링 리포트 Job (Issue #251 Phase 4)
 *
 * **기능**
 * - 매시간 정기 모니터링 리포트 생성
 * - AlertPort를 통한 시스템 상태 요약 전송 (Hexagonal Architecture)
 * - Leader Election으로 단일 인스턴스 실행
 *
 * **CLAUDE.md 준수사항**
 * - Section 12 (LogicExecutor): 배치 작업도 executor 패턴
 * - Section 12-1 (Resilience): 분산 락으로 중복 실행 방지
 * - ADR-003: Hexagonal Architecture - AlertPort (outbound port) 사용
 *
 * @see SystemMetricsPort
 * @see AlertPort
 */
@Component
class MonitoringReportJob(
    private val systemMetrics: SystemMetricsPort,
    private val lockStrategy: LockStrategy,
    @Value("\${ai.sre.enabled:false}") private val aiSreEnabled: Boolean = false,
) {

    // Alert Port (Optional - hexagonal architecture adapter)
    @Autowired(required = false)
    private var alertPort: AlertPort? = null

    /** 매시간 정기 리포트 (정각 실행) */
    @Scheduled(cron = "0 0 * * * *")
    fun generateHourlyReport() {
        executeReportJob("hourly")
    }

    /** 일간 요약 리포트 (매일 오전 9시) */
    @Scheduled(cron = "0 0 9 * * *")
    fun generateDailyReport() {
        executeReportJob("daily")
    }

    /** 리포트 Job 실행 (Leader Election 적용) */
    private fun executeReportJob(reportType: String) {
        if (!aiSreEnabled) {
            log.debug("[MonitoringReport] AI SRE 비활성화 - {} 리포트 스킵", reportType)
            return
        }

        // Leader Election: xact-scoped lock으로 단일 인스턴스만 실행 (#628)
        lockStrategy.executeWithLock(REPORT_LOCK_KEY, 10, LOCK_LEASE_SECONDS.toLong()) {
            generateAndSendReport(reportType)
        }
    }

    /** 리포트 생성 및 전송 */
    private fun generateAndSendReport(reportType: String) {
        log.info("[MonitoringReport] {} 리포트 생성 시작", reportType)

        // 1. 메트릭 수집
        @Suppress("UNCHECKED_CAST")
        val allMetrics = systemMetrics.collectAllMetrics() as Map<MetricCategory, Map<String, Any>>

        // 2. 리포트 메시지 생성
        val report = createReportMessage(reportType, allMetrics)

        // 3. Alert 전송 (AlertPort via hexagonal architecture)
        alertPort?.let { sendReport(report, it) }

        log.info("[MonitoringReport] {} 리포트 생성 완료", reportType)
    }

    /** 리포트 AlertMessage 생성 (Hexagonal Architecture용 포맷) */
    private fun createReportMessage(
        reportType: String,
        metrics: Map<MetricCategory, Map<String, Any>>,
    ): AlertMessage {
        val title = if (reportType == "daily") "📊 일간 시스템 리포트" else "📈 시간별 시스템 리포트"
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

        // 모든 메트릭을 텍스트 포맷으로 조합
        val message = StringBuilder()
        message.append("**시스템 상태 정기 리포트** ($timestamp)\n\n")

        // Golden Signals 요약
        message.append("### 🎯 Golden Signals\n")
        message.append(formatGoldenSignals(metrics.getOrDefault(MetricCategory.GOLDEN_SIGNALS, emptyMap())))
        message.append("\n")

        // JVM 상태
        message.append("### ☕ JVM Status\n")
        message.append(formatJvmStatus(metrics.getOrDefault(MetricCategory.JVM, emptyMap())))
        message.append("\n")

        // Circuit Breaker 상태
        message.append("### 🔌 Circuit Breakers\n")
        message.append(formatCircuitBreakers(metrics.getOrDefault(MetricCategory.CIRCUIT_BREAKER, emptyMap())))
        message.append("\n")

        // Database 상태
        message.append("### 🗄️ Database\n")
        message.append(formatDatabaseStatus(metrics.getOrDefault(MetricCategory.DATABASE, emptyMap())))
        message.append("\n")

        // Redis 상태
        message.append("### 📦 Redis Buffer\n")
        message.append(formatRedisStatus(metrics.getOrDefault(MetricCategory.REDIS, emptyMap())))

        return AlertMessage.of(title, message.toString())
    }

    private fun formatGoldenSignals(metrics: Map<String, Any>): String {
        val sb = StringBuilder()
        sb.append("- Latency p95: ").append(metrics.getOrDefault("latency_p95_ms", "N/A")).append("ms\n")
        sb.append("- Error Rate: ").append(metrics.getOrDefault("error_rate_percent", "0.0")).append("%\n")
        sb.append("- DB Saturation: ").append(metrics.getOrDefault("db_pool_saturation_percent", "N/A")).append("%")
        return sb.toString()
    }

    private fun formatJvmStatus(metrics: Map<String, Any>): String {
        val sb = StringBuilder()
        sb.append("- Heap: ").append(metrics.getOrDefault("heap_used_mb", "?")).append("/")
        sb.append(metrics.getOrDefault("heap_max_mb", "?")).append("MB\n")
        sb.append("- Threads: ").append(metrics.getOrDefault("threads_live", "?"))
        return sb.toString()
    }

    private fun formatCircuitBreakers(metrics: Map<String, Any>): String {
        val openCount = (metrics.getOrDefault("summary_open_count", 0L) as Number).toLong()
        val halfOpenCount = (metrics.getOrDefault("summary_half_open_count", 0L) as Number).toLong()
        val totalCount = (metrics.getOrDefault("summary_total_count", 0L) as Number).toLong()

        val status = if (openCount > 0) "⚠️ DEGRADED" else "✅ HEALTHY"
        return String.format(
            "- Status: %s\n- Open: %d, Half-Open: %d/%d",
            status,
            openCount,
            halfOpenCount,
            totalCount,
        )
    }

    private fun formatDatabaseStatus(metrics: Map<String, Any>): String = String.format(
        "- Active: %s/%s\n- Saturation: %s%%",
        metrics.getOrDefault("connections_active", "?"),
        metrics.getOrDefault("connections_max", "?"),
        metrics.getOrDefault("saturation_percent", "0"),
    )

    private fun formatRedisStatus(metrics: Map<String, Any>): String = String.format(
        "- Pending: %s\n- Saturation: %s%%",
        metrics.getOrDefault("buffer_pending_count", "0"),
        metrics.getOrDefault("buffer_saturation_percent", "0"),
    )

    /** AlertPort로 리포트 전송 */
    private fun sendReport(report: AlertMessage, port: AlertPort) {
        log.info("[MonitoringReport] Alert 전송 시작: {}", report.title)
        val sent = port.sendAlert(report)
        if (sent) {
            log.info("[MonitoringReport] Alert 전송 성공")
        } else {
            log.warn("[MonitoringReport] Alert 전송 실패")
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(MonitoringReportJob::class.java)
        private const val REPORT_LOCK_KEY = "monitoring-report-lock"
        private const val LOCK_LEASE_SECONDS = 60 // 리포트 생성 최대 시간
    }
}
