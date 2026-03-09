package maple.expectation.infrastructure.config

import maple.expectation.infrastructure.aop.context.SkipEquipmentL2CacheContext
import org.springframework.core.task.TaskDecorator

/**
 * Task Decorator Factory - MDC + Cache Context 전파용 TaskDecorator 생성
 *
 * ## 책임
 *
 * - MDC (Mapped Diagnostic Context) 전파
 * - SkipEquipmentL2CacheContext 전파
 * - ThreadLocal 상태 스냅샷/복원 (snapshot/restore 패턴)
 *
 * ## 불변식 3 준수
 *
 * 모든 비동기 실행 지점에서 ThreadLocal 상태가 전파되어야 함
 *
 * ## MDCFilter 연계
 *
 * HTTP 요청 진입 시 [maple.expectation.infrastructure.filter.MDCFilter]가 설정한 requestId가 이
 * TaskDecorator를 통해 비동기 워커 스레드로 전파됩니다.
 *
 * ## 전파 원리 (snapshot/restore 패턴)
 *
 * 1. 호출 스레드에서 contextMap = MDC.getCopyOfContextMap(), snap = snapshot()
 * 2. 워커 스레드 진입 시 MDC.setContextMap(contextMap), restore(snap)
 * 3. 작업 완료 후 finally에서 MDC.clear(), restore(before)로 원복
 */
class TaskDecoratorFactory {

    /**
     * MDC + SkipEquipmentL2CacheContext 전파용 TaskDecorator 생성
     *
     * @return TaskDecorator 인스턴스
     * @see maple.expectation.infrastructure.filter.MDCFilter
     */
    fun createContextPropagatingDecorator(): TaskDecorator = TaskDecorator { runnable ->
        // 1. 호출 스레드에서 현재 상태 캡처
        val mdcContextMap = org.slf4j.MDC.getCopyOfContextMap()
        val cacheContextSnap = SkipEquipmentL2CacheContext.snapshot() // V5: MDC 기반

        Runnable {
            // 2. 워커 스레드에서 기존 상태 백업
            val mdcBefore = org.slf4j.MDC.getCopyOfContextMap()
            val cacheContextBefore = SkipEquipmentL2CacheContext.snapshot() // V5: MDC 기반

            // 3. 캡처된 상태로 설정
            if (mdcContextMap != null) {
                org.slf4j.MDC.setContextMap(mdcContextMap)
            } else {
                org.slf4j.MDC.clear()
            }
            SkipEquipmentL2CacheContext.restore(cacheContextSnap)

            try {
                runnable.run()
            } finally {
                // 4. 작업 완료 후 원래 상태로 복원 (스레드풀 누수 방지)
                if (mdcBefore != null) {
                    org.slf4j.MDC.setContextMap(mdcBefore)
                } else {
                    org.slf4j.MDC.clear()
                }
                SkipEquipmentL2CacheContext.restore(cacheContextBefore)
            }
        }
    }
}
