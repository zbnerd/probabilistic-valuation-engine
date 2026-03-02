package maple.expectation.service.v2.like.compensation;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.core.dto.like.FetchResult;
import maple.expectation.core.port.out.like.CompensationCommand;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.service.v2.like.event.LikeSyncFailedEvent;
import maple.expectation.service.v2.like.strategy.AtomicFetchStrategy;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Redis 보상 트랜잭션 명령 구현 (Command Pattern)
 *
 * <p>금융수준 안전 설계:
 *
 * <ul>
 *   <li><b>Thread-Safe</b>: AtomicReference/AtomicBoolean으로 동시성 보장
 *   <li><b>멱등성</b>: compensate()는 한 번만 실행됨
 *   <li><b>Graceful Degradation</b>: 복구 실패 시 로그만 남기고 진행
 * </ul>
 *
 * @since 2.0.0
 */
@Slf4j
public class RedisCompensationCommand implements CompensationCommand {

  private final String sourceKey;
  private final AtomicFetchStrategy strategy;
  private final LogicExecutor executor;
  private final MeterRegistry meterRegistry;
  private final ApplicationEventPublisher eventPublisher;

  private final AtomicReference<FetchResult> savedResult = new AtomicReference<>(null);
  private final AtomicBoolean committed = new AtomicBoolean(false);

  /**
   * 보상 명령 생성
   *
   * @param sourceKey 원본 키 ({buffer:likes})
   * @param strategy 원자적 fetch 전략
   * @param executor 실행 엔진
   * @param meterRegistry 메트릭 레지스트리
   * @param eventPublisher 이벤트 발행자 (DLQ 패턴)
   */
  public RedisCompensationCommand(
      String sourceKey,
      AtomicFetchStrategy strategy,
      LogicExecutor executor,
      MeterRegistry meterRegistry,
      ApplicationEventPublisher eventPublisher) {
    this.sourceKey = sourceKey;
    this.strategy = strategy;
    this.executor = executor;
    this.meterRegistry = meterRegistry;
    this.eventPublisher = eventPublisher;
  }

  @Override
  public void save(FetchResult result) {
    if (result == null || result.isEmpty()) {
      return;
    }
    savedResult.set(result);
    log.debug("Compensation state saved: tempKey={}, entries={}", result.tempKey(), result.size());
  }

  @Override
  public void compensate() {
    FetchResult result = savedResult.get();
    if (result == null || result.isEmpty()) {
      return;
    }

    // 이미 커밋됨 → 보상 불필요
    if (committed.get()) {
      return;
    }

    // 메트릭 기록 (보상 트랜잭션 발동)
    recordCompensationTriggered();

    executor.executeOrCatch(
        () -> {
          strategy.restore(result.tempKey(), sourceKey);
          log.warn(
              "Compensation triggered: tempKey={} → sourceKey={}, entries={}",
              result.tempKey(),
              sourceKey,
              result.size());
          return null;
        },
        e -> {
          // P0 FIX: 복구 실패 시 DLQ 이벤트 발행 (데이터 영구 손실 방지)
          log.error(
              "Compensation FAILED - Publishing DLQ event: tempKey={}, sourceKey={}, reason={}",
              result.tempKey(),
              sourceKey,
              e.getMessage());
          publishDlqEvent(result, e);
          return null;
        },
        TaskContext.of("Compensation", "restore", result.tempKey()));
  }

  // ========== DLQ (Dead Letter Queue) ==========

  /**
   * DLQ 이벤트 발행 (복구 실패 시)
   *
   * <p>금융수준 안전 설계:
   *
   * <ul>
   *   <li>이벤트 발행 실패해도 로그에 데이터 기록
   *   <li>Listener에서 파일 백업 + Discord 알림
   * </ul>
   */
  private void publishDlqEvent(FetchResult result, Throwable cause) {
    executor.executeOrCatch(
        () -> {
          LikeSyncFailedEvent event = LikeSyncFailedEvent.fromFetchResult(result, sourceKey, cause);
          eventPublisher.publishEvent(event);
          log.info("DLQ event published: tempKey={}, entries={}", result.tempKey(), result.size());
          return null;
        },
        e -> {
          // 이벤트 발행마저 실패 → 로그에 데이터 직접 기록 (최후의 보루)
          log.error(
              "🚨 [CRITICAL] DLQ event publish failed! Data logged for manual recovery: {}",
              result.data(),
              e);
          return null;
        },
        TaskContext.of("Compensation", "publishDlqEvent", result.tempKey()));
  }

  // ========== Metrics (Micrometer) ==========

  /** 보상 트랜잭션 발동 메트릭 기록 */
  private void recordCompensationTriggered() {
    meterRegistry.counter("cache.compensation.triggered").increment();
  }

  @Override
  public void commit() {
    FetchResult result = savedResult.get();
    if (result == null || result.isEmpty()) {
      committed.set(true);
      return;
    }

    // CAS로 중복 커밋 방지
    if (!committed.compareAndSet(false, true)) {
      return;
    }

    executor.executeOrCatch(
        () -> {
          strategy.deleteTempKey(result.tempKey());
          log.debug("Compensation committed: tempKey={} deleted", result.tempKey());
          return null;
        },
        e -> {
          // 삭제 실패 시 TTL에 의해 자동 만료됨
          log.warn("TempKey delete failed (will expire by TTL): tempKey={}", result.tempKey());
          return null;
        },
        TaskContext.of("Compensation", "commit", result.tempKey()));
  }

  @Override
  public boolean isPending() {
    return savedResult.get() != null && !committed.get();
  }
}
