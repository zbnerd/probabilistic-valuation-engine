package maple.expectation.batch;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.core.domain.model.AlertMessage;
import maple.expectation.core.port.out.AlertPort;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.lock.LockStrategy;
import maple.expectation.infrastructure.monitoring.collector.MetricCategory;
import maple.expectation.monitoring.context.SystemContextProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 모니터링 리포트 Job (Issue #251 Phase 4)
 *
 * <h3>기능</h3>
 *
 * <ul>
 *   <li>매시간 정기 모니터링 리포트 생성
 *   <li>AlertPort를 통한 시스템 상태 요약 전송 (Hexagonal Architecture)
 *   <li>Leader Election으로 단일 인스턴스 실행
 * </ul>
 *
 * <h4>CLAUDE.md 준수사항</h4>
 *
 * <ul>
 *   <li>Section 12 (LogicExecutor): 배치 작업도 executor 패턴
 *   <li>Section 12-1 (Resilience): 분산 락으로 중복 실행 방지
 *   <li>ADR-003: Hexagonal Architecture - AlertPort (outbound port) 사용
 * </ul>
 *
 * @see SystemContextProvider
 * @see AlertPort
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonitoringReportJob {

  private final SystemContextProvider contextProvider;
  private final LockStrategy lockStrategy;
  private final LogicExecutor executor;

  // Alert Port (Optional - hexagonal architecture adapter)
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private AlertPort alertPort;

  @Value("${ai.sre.enabled:false}")
  private boolean aiSreEnabled;

  private static final String REPORT_LOCK_KEY = "monitoring-report-lock";
  private static final int LOCK_LEASE_SECONDS = 60; // 리포트 생성 최대 시간

  /** 매시간 정기 리포트 (정각 실행) */
  @Scheduled(cron = "0 0 * * * *")
  public void generateHourlyReport() {
    executeReportJob("hourly");
  }

  /** 일간 요약 리포트 (매일 오전 9시) */
  @Scheduled(cron = "0 0 9 * * *")
  public void generateDailyReport() {
    executeReportJob("daily");
  }

  /** 리포트 Job 실행 (Leader Election 적용) */
  private void executeReportJob(String reportType) {
    if (!aiSreEnabled) {
      log.debug("[MonitoringReport] AI SRE 비활성화 - {} 리포트 스킵", reportType);
      return;
    }

    TaskContext context = TaskContext.of("Batch", "MonitoringReport", reportType);

    // Leader Election: 단일 인스턴스만 실행
    boolean isLeader = lockStrategy.tryLockImmediately(REPORT_LOCK_KEY, LOCK_LEASE_SECONDS);
    if (!isLeader) {
      log.debug("[MonitoringReport] 리더 선출 실패 - 다른 인스턴스가 실행 중");
      return;
    }

    executor.executeWithFinally(
        () -> {
          generateAndSendReport(reportType);
          return null;
        },
        () -> {
          lockStrategy.unlock(REPORT_LOCK_KEY);
          log.debug("[MonitoringReport] 리더 락 해제: {}", REPORT_LOCK_KEY);
        },
        context);
  }

  /** 리포트 생성 및 전송 */
  private void generateAndSendReport(String reportType) {
    log.info("[MonitoringReport] {} 리포트 생성 시작", reportType);

    // 1. 메트릭 수집
    Map<MetricCategory, Map<String, Object>> allMetrics = contextProvider.collectAllMetrics();

    // 2. 리포트 메시지 생성
    AlertMessage report = createReportMessage(reportType, allMetrics);

    // 3. Alert 전송 (AlertPort via hexagonal architecture)
    if (alertPort != null) {
      sendReport(report);
    }

    log.info("[MonitoringReport] {} 리포트 생성 완료", reportType);
  }

  /** 리포트 AlertMessage 생성 (Hexagonal Architecture용 포맷) */
  private AlertMessage createReportMessage(
      String reportType, Map<MetricCategory, Map<String, Object>> metrics) {
    String title = reportType.equals("daily") ? "📊 일간 시스템 리포트" : "📈 시간별 시스템 리포트";
    String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

    // 모든 메트릭을 텍스트 포맷으로 조합
    StringBuilder message = new StringBuilder();
    message.append("**시스템 상태 정기 리포트** (").append(timestamp).append(")\\n\\n");

    // Golden Signals 요약
    message.append("### 🎯 Golden Signals\\n");
    message.append(
        formatGoldenSignals(metrics.getOrDefault(MetricCategory.GOLDEN_SIGNALS, Map.of())));
    message.append("\\n");

    // JVM 상태
    message.append("### ☕ JVM Status\\n");
    message.append(formatJvmStatus(metrics.getOrDefault(MetricCategory.JVM, Map.of())));
    message.append("\\n");

    // Circuit Breaker 상태
    message.append("### 🔌 Circuit Breakers\\n");
    message.append(
        formatCircuitBreakers(metrics.getOrDefault(MetricCategory.CIRCUIT_BREAKER, Map.of())));
    message.append("\\n");

    // Database 상태
    message.append("### 🗄️ Database\\n");
    message.append(formatDatabaseStatus(metrics.getOrDefault(MetricCategory.DATABASE, Map.of())));
    message.append("\\n");

    // Redis 상태
    message.append("### 📦 Redis Buffer\\n");
    message.append(formatRedisStatus(metrics.getOrDefault(MetricCategory.REDIS, Map.of())));

    return AlertMessage.of(title, message.toString());
  }

  private String formatGoldenSignals(Map<String, Object> metrics) {
    StringBuilder sb = new StringBuilder();
    sb.append("- Latency p95: ")
        .append(metrics.getOrDefault("latency_p95_ms", "N/A"))
        .append("ms\\n");
    sb.append("- Error Rate: ")
        .append(metrics.getOrDefault("error_rate_percent", "0.0"))
        .append("%\\n");
    sb.append("- DB Saturation: ")
        .append(metrics.getOrDefault("db_pool_saturation_percent", "N/A"))
        .append("%");
    return sb.toString();
  }

  private String formatJvmStatus(Map<String, Object> metrics) {
    StringBuilder sb = new StringBuilder();
    sb.append("- Heap: ").append(metrics.getOrDefault("heap_used_mb", "?")).append("/");
    sb.append(metrics.getOrDefault("heap_max_mb", "?")).append("MB\\n");
    sb.append("- Threads: ").append(metrics.getOrDefault("threads_live", "?"));
    return sb.toString();
  }

  private String formatCircuitBreakers(Map<String, Object> metrics) {
    Long openCount = (Long) metrics.getOrDefault("summary_open_count", 0L);
    Long halfOpenCount = (Long) metrics.getOrDefault("summary_half_open_count", 0L);
    Long totalCount = (Long) metrics.getOrDefault("summary_total_count", 0L);

    String status = openCount > 0 ? "⚠️ DEGRADED" : "✅ HEALTHY";
    return String.format(
        "- Status: %s\\n- Open: %d, Half-Open: %d/%d",
        status, openCount, halfOpenCount, totalCount);
  }

  private String formatDatabaseStatus(Map<String, Object> metrics) {
    return String.format(
        "- Active: %s/%s\\n- Saturation: %s%%",
        metrics.getOrDefault("connections_active", "?"),
        metrics.getOrDefault("connections_max", "?"),
        metrics.getOrDefault("saturation_percent", "0"));
  }

  private String formatRedisStatus(Map<String, Object> metrics) {
    return String.format(
        "- Pending: %s\\n- Saturation: %s%%",
        metrics.getOrDefault("buffer_pending_count", "0"),
        metrics.getOrDefault("buffer_saturation_percent", "0"));
  }

  /** AlertPort로 리포트 전송 */
  private void sendReport(AlertMessage report) {
    log.info("[MonitoringReport] Alert 전송 시작: {}", report.getTitle());
    boolean sent = alertPort.sendAlert(report);
    if (sent) {
      log.info("[MonitoringReport] Alert 전송 성공");
    } else {
      log.warn("[MonitoringReport] Alert 전송 실패");
    }
  }

  /** 리포트 실패 처리 */
  private void handleReportFailure(String reportType, Throwable t) {
    log.error("[MonitoringReport] {} 리포트 생성 실패: {}", reportType, t.getMessage(), t);
  }
}
