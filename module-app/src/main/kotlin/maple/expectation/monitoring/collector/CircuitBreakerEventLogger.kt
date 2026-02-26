package maple.expectation.monitoring.collector

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * CircuitBreaker 상태 전이 이벤트 로거 (P1-5)
 *
 * <h3>목적</h3>
 *
 * <p>서킷브레이커 상태 전이(CLOSED→OPEN→HALF_OPEN 등)를 로그로 기록하여 운영 가시성을 확보합니다.
 *
 * <h3>기록 대상</h3>
 *
 * <ul>
 *   <li>상태 전이 (State Transition): WARN 레벨
 *   <li>예외 기록 (Error): ERROR 레벨 (WARN으로 낮춤 - 이미 handler에서 처리)
 * </ul>
 */
@Component
class CircuitBreakerEventLogger(
    private val circuitBreakerRegistry: CircuitBreakerRegistry
) {

  private val log = LoggerFactory.getLogger(CircuitBreakerEventLogger::class.java)

  @PostConstruct
  fun registerEventListeners() {
    circuitBreakerRegistry.getAllCircuitBreakers()
        .forEach { registerStateTransitionListener(it) }

    circuitBreakerRegistry.eventPublisher.onEntryAdded { event ->
      registerStateTransitionListener(event.addedEntry)
    }
  }

  private fun registerStateTransitionListener(cb: CircuitBreaker) {
    cb.eventPublisher
        .onStateTransition { event ->
          log.warn(
              "[CircuitBreaker:{}] State transition: {} → {}",
              event.circuitBreakerName,
              event.stateTransition.fromState,
              event.stateTransition.toState
          )
        }
        .onSlowCallRateExceeded { event ->
          log.warn(
              "[CircuitBreaker:{}] Slow call rate exceeded: {}%",
              event.circuitBreakerName, event.slowCallRate
          )
        }
        .onFailureRateExceeded { event ->
          log.warn(
              "[CircuitBreaker:{}] Failure rate exceeded: {}%",
              event.circuitBreakerName, event.failureRate
          )
        }
  }
}
