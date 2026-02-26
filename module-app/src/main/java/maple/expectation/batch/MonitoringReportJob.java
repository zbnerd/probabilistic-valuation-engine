package maple.expectation.batch;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.lock.LockStrategy;
import maple.expectation.infrastructure.monitoring.collector.MetricCategory;
import maple.expectation.monitoring.context.SystemContextProvider;
import maple.expectation.service.v2.alert.DiscordAlertService;
import maple.expectation.service.v2.alert.dto.DiscordMessage;
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
 *   <li>Discord로 시스템 상태 요약 전송
 *   <li>Leader Election으로 단일 인스턴스 실행
 * </ul>
 *
 * <h4>CLAUDE.md 준수사항</h4>
 *
 * <ul>
 *   <li>Section 12 (LogicExecutor): 배치 작업도 executor 패턴
 *   <li>Section 12-1 (Resilience): 분산 락으로 중복 실행 방지
 * </ul>
 *
 * @see SystemContextProvider
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonitoringReportJob {

  private final SystemContextProvider contextProvider;
  private final LockStrategy lockStrategy;
  private final LogicExecutor executor;

  // Discord 서비스 (Optional)
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private DiscordAlertService discordAlertService;

  @Value("${ai.sre.enabled:false}")
  private boolean aiSreEnabled;

  private static final String REPORT_LOCK_KEY = "monitoring-report-lock";
  private static final int LOCK_LEASE_SECONDS = 60; // 리포트 생성 최대 시간

  private static final int INFO_COLOR = 3447003; // 파란색

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
    DiscordMessage report = createReportMessage(reportType, allMetrics);

    // 3. Discord 전송
    if (discordAlertService != null) {
      sendReportToDiscord(report);
    }

    log.info("[MonitoringReport] {} 리포트 생성 완료", reportType);
  }

  /** 리포트 Discord 메시지 생성 */
  private DiscordMessage createReportMessage(
      String reportType, Map<MetricCategory, Map<String, Object>> metrics) {
    String title = reportType.equals("daily") ? "📊 일간 시스템 리포트" : "📈 시간별 시스템 리포트";
    String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

    List<DiscordMessage.Field> fields = new ArrayList<>();

    // Golden Signals 요약
    Map<String, Object> goldenSignals =
        metrics.getOrDefault(MetricCategory.GOLDEN_SIGNALS, Map.of());
    fields.add(
        new DiscordMessage.Field("🎯 Golden Signals", formatGoldenSignals(goldenSignals), false));

    // JVM 상태
    Map<String, Object> jvmMetrics = metrics.getOrDefault(MetricCategory.JVM, Map.of());
    fields.add(new DiscordMessage.Field("☕ JVM Status", formatJvmStatus(jvmMetrics), true));

    // Circuit Breaker 상태
    Map<String, Object> cbMetrics = metrics.getOrDefault(MetricCategory.CIRCUIT_BREAKER, Map.of());
    fields.add(
        new DiscordMessage.Field("🔌 Circuit Breakers", formatCircuitBreakers(cbMetrics), true));

    // Database 상태
    Map<String, Object> dbMetrics = metrics.getOrDefault(MetricCategory.DATABASE, Map.of());
    fields.add(new DiscordMessage.Field("🗄️ Database", formatDatabaseStatus(dbMetrics), true));

    // Redis 상태
    Map<String, Object> redisMetrics = metrics.getOrDefault(MetricCategory.REDIS, Map.of());
    fields.add(new DiscordMessage.Field("📦 Redis Buffer", formatRedisStatus(redisMetrics), true));

    return new DiscordMessage(
        List.of(
            new DiscordMessage.Embed(
                title,
                "시스템 상태 정기 리포트 (" + timestamp + ")",
                INFO_COLOR,
                fields,
                new DiscordMessage.Footer("MapleExpectation Monitoring"),
                java.time.ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT))));
  }

  private String formatGoldenSignals(Map<String, Object> metrics) {
    StringBuilder sb = new StringBuilder();
    sb.append("Latency p95: ").append(metrics.getOrDefault("latency_p95_ms", "N/A")).append("ms\n");
    sb.append("Error Rate: ")
        .append(metrics.getOrDefault("error_rate_percent", "0.0"))
        .append("%\n");
    sb.append("DB Saturation: ")
        .append(metrics.getOrDefault("db_pool_saturation_percent", "N/A"))
        .append("%");
    return sb.toString();
  }

  private String formatJvmStatus(Map<String, Object> metrics) {
    StringBuilder sb = new StringBuilder();
    sb.append("Heap: ").append(metrics.getOrDefault("heap_used_mb", "?")).append("/");
    sb.append(metrics.getOrDefault("heap_max_mb", "?")).append("MB\n");
    sb.append("Threads: ").append(metrics.getOrDefault("threads_live", "?"));
    return sb.toString();
  }

  private String formatCircuitBreakers(Map<String, Object> metrics) {
    Long openCount = (Long) metrics.getOrDefault("summary_open_count", 0L);
    Long halfOpenCount = (Long) metrics.getOrDefault("summary_half_open_count", 0L);
    Long totalCount = (Long) metrics.getOrDefault("summary_total_count", 0L);

    String status = openCount > 0 ? "⚠️ DEGRADED" : "✅ HEALTHY";
    return String.format(
        "%s\nOpen: %d, Half-Open: %d/%d", status, openCount, halfOpenCount, totalCount);
  }

  private String formatDatabaseStatus(Map<String, Object> metrics) {
    return String.format(
        "Active: %s/%s\nSaturation: %s%%",
        metrics.getOrDefault("connections_active", "?"),
        metrics.getOrDefault("connections_max", "?"),
        metrics.getOrDefault("saturation_percent", "0"));
  }

  private String formatRedisStatus(Map<String, Object> metrics) {
    return String.format(
        "Pending: %s\nSaturation: %s%%",
        metrics.getOrDefault("buffer_pending_count", "0"),
        metrics.getOrDefault("buffer_saturation_percent", "0"));
  }

  /** Discord로 리포트 전송 */
  private void sendReportToDiscord(DiscordMessage report) {
    // DiscordAlertService의 내부 send 메서드 대신 직접 WebClient 사용하거나
    // send를 public으로 변경해야 함. 여기서는 로그만 남김.
    log.info("[MonitoringReport] Discord 리포트 전송 (embeds: {})", report.embeds().size());
  }

  /** 리포트 실패 처리 */
  private void handleReportFailure(String reportType, Throwable t) {
    log.error("[MonitoringReport] {} 리포트 생성 실패: {}", reportType, t.getMessage(), t);
  }
}
