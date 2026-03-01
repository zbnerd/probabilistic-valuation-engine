package maple.expectation.infrastructure.monitoring

import maple.expectation.core.port.out.BufferStatusQuery
import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.MonitoringException
import maple.expectation.infrastructure.alert.StatelessAlertService
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.lock.LockStrategy
import maple.expectation.infrastructure.config.MonitoringThresholdProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 버퍼 포화도 모니터링 서비스
 *
 * <p>주기적으로 버퍼 상태를 확인하고 임계값 초과 시 알림을 발송합니다.
 *
 * <h2>DIP 준수</h2>
 *
 * <p>{@link BufferStatusQuery} Port를 통해 버퍼 상태를 조회하므로 Repository 직접 참조를 방지합니다.
 *
 * @see BufferStatusQuery 버퍼 상태 조회 Port
 */
@Component
class MonitoringAlertService(
    private val bufferStatus: BufferStatusQuery,
    private val statelessAlertService: StatelessAlertService,
    private val lockStrategy: LockStrategy,
    private val executor: LogicExecutor,
    private val thresholdProperties: MonitoringThresholdProperties
) {

  private val log = LoggerFactory.getLogger(MonitoringAlertService::class.java)

  /**
   * 버퍼 포화도 체크 로직 (Leader Election 패턴)
   *
   * tryLockImmediately()를 사용하여 예외 없이 리더 선출. 락 획득 실패(Follower)는 정상 시나리오이므로 조용히 스킵.
   */
  @Scheduled(fixedRate = 5000)
  fun checkBufferSaturation() {
    val context = TaskContext.of("Monitoring", "CheckSaturation")

    val isLeader = lockStrategy.tryLockImmediately("global-monitoring-lock", 4)

    if (!isLeader) {
      log.debug("⏭️ [Monitoring] 리더 선출 실패 - 다른 인스턴스가 리더입니다. 체크 스킵.")
      return
    }

    executor.executeOrCatch(
        { performBufferCheck(); null },
        { t -> handleMonitoringFailure(t) },
        context
    )
  }

  /** 헬퍼 1: 실제 수치 확인 및 알림 로직 (로직 응집도 향상) */
  private fun performBufferCheck() {
    val globalPending = bufferStatus.getTotalPendingCount()

    if (globalPending > thresholdProperties.bufferSaturationCount) {
      val exception = MonitoringException(CommonErrorCode.SYSTEM_CAPACITY_EXCEEDED, globalPending)

      executor.executeVoidJava(
          {
            statelessAlertService.sendCritical(
                "🚨 GLOBAL BUFFER SATURATION", exception.message ?: "Unknown error", exception
            )
            log.warn("[{}] {}", exception.errorCode.code, exception.message)
          },
          TaskContext.of("Alert", "SendDiscord", globalPending.toString())
      )
    }
  }

  /** 모니터링 실패 대응 (실제 장애만 로깅) */
  private fun handleMonitoringFailure(t: Throwable): Void? {
    log.error("❌ [Monitoring] 버퍼 모니터링 중 장애 발생: {}", t.message, t)
    return null
  }
}
