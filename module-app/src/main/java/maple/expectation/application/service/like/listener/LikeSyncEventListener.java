package maple.expectation.application.service.like.listener;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.alert.StatelessAlertService;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.queue.like.event.LikeSyncFailedEvent;
import maple.expectation.service.v2.shutdown.ShutdownDataPersistenceService;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * LikeSync 실패 이벤트 리스너 (DLQ 패턴)
 *
 * <p>금융수준 안전 설계:
 *
 * <ul>
 *   <li>Stateless 알림 발송 (운영팀 즉시 인지)
 *   <li>파일 백업 (수동 복구 가능)
 *   <li>메트릭 기록 (모니터링)
 * </ul>
 *
 * @since 2.0.0
 */
@Slf4j
@Component
public class LikeSyncEventListener {

  private final StatelessAlertService statelessAlertService;
  private final ShutdownDataPersistenceService persistenceService;
  private final LogicExecutor executor;
  private final MeterRegistry meterRegistry;

  public LikeSyncEventListener(
      StatelessAlertService statelessAlertService,
      ShutdownDataPersistenceService persistenceService,
      LogicExecutor executor,
      MeterRegistry meterRegistry) {
    this.statelessAlertService = statelessAlertService;
    this.persistenceService = persistenceService;
    this.executor = executor;
    this.meterRegistry = meterRegistry;
  }

  /**
   * 동기화 실패 이벤트 처리 (비동기)
   *
   * <p>처리 순서:
   *
   * <ol>
   *   <li>파일 백업 (데이터 보존 최우선)
   *   <li>메트릭 기록
   *   <li>Discord 알림
   * </ol>
   */
  @Async
  @EventListener
  public void handleSyncFailure(LikeSyncFailedEvent event) {
    TaskContext context = TaskContext.of("LikeSync", "FailureHandler", event.getTempKey());

    // Step 1: 파일 백업 (최우선 - 데이터 보존)
    backupToFile(event, context);

    // Step 2: 메트릭 기록
    recordFailureMetric(event);

    // Step 3: Discord 알림
    sendDiscordAlert(event, context);

    log.error(
        "‼️ [DLQ] LikeSync 복구 실패 - 파일 백업 완료. "
            + "tempKey={}, sourceKey={}, entries={}, totalCount={}, error={}",
        event.getTempKey(),
        event.getSourceKey(),
        event.size(),
        event.totalCount(),
        event.getErrorMessage());
  }

  // ========== Private Methods ==========

  /**
   * 파일 백업 (데이터 보존 최우선)
   *
   * <p>ShutdownDataPersistenceService 재사용으로 일관된 백업 형식 유지
   */
  private void backupToFile(LikeSyncFailedEvent event, TaskContext context) {
    executor.executeOrCatch(
        () -> {
          event
              .getData()
              .forEach((userIgn, count) -> persistenceService.appendLikeEntry(userIgn, count));
          log.info("💾 [DLQ] 파일 백업 완료: {} entries", event.size());
          return null;
        },
        e -> {
          // 파일 백업마저 실패 → 최악의 상황, 로그에 데이터 직접 기록
          log.error("🚨 [CRITICAL] 파일 백업 실패! 데이터 직접 로깅: {}", event.getData(), e);
          return null;
        },
        context);
  }

  /** 실패 메트릭 기록 */
  private void recordFailureMetric(LikeSyncFailedEvent event) {
    meterRegistry
        .counter("like.sync.dlq.triggered", "type", event.getTempKey() != null ? "batch" : "single")
        .increment();

    meterRegistry.counter("like.sync.dlq.entries").increment(event.size());
    meterRegistry.counter("like.sync.dlq.total_count").increment(event.totalCount());
  }

  /** Discord 알림 발송 */
  private void sendDiscordAlert(LikeSyncFailedEvent event, TaskContext context) {
    executor.executeOrCatch(
        () -> {
          statelessAlertService.sendCritical(
              "🚨 좋아요 동기화 DLQ 발생",
              String.format(
                  "유실 위험 데이터: %d건 (%d개 엔트리)\n" + "임시키: %s\n원본키: %s\n" + "⚠️ 파일 백업 완료 - 수동 복구 필요",
                  event.totalCount(), event.size(), event.getTempKey(), event.getSourceKey()),
              event.exception());
          return null;
        },
        e -> {
          log.warn("Discord 알림 발송 실패 (데이터는 파일에 백업됨): {}", e.getMessage());
          return null;
        },
        context);
  }
}
