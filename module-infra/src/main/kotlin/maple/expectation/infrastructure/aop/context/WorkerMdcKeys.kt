package maple.expectation.infrastructure.aop.context

import org.slf4j.MDC

/**
 * 워커 MDC 키 관리
 *
 * 워커 태스크 처리 시 MDC에 설정되는 컨텍스트 키를 관리합니다.
 * WorkerTimingAspect가 이 키들을 읽어 메트릭 태그와 로그에 포함합니다.
 *
 * ## 사용 패턴
 * ```kotlin
 * try {
 *     WorkerMdcKeys.putTaskContext(taskId, queueName, priority)
 *     worker.processTask(task)  // @TimedTask, @TimedStage가 MDC 값을 읽음
 * } finally {
 *     WorkerMdcKeys.clearTaskContext()
 * }
 * ```
 *
 * ## 스레드 안전성
 * MDC는 ThreadLocal 기반이므로 각 스레드마다 독립적인 컨텍스트를 가집니다.
 * 반드시 finally에서 clearTaskContext()를 호출하여 스레드 풀 누수를 방지해야 합니다.
 */
object WorkerMdcKeys {

    const val TASK_ID = "taskId"
    const val QUEUE_NAME = "queueName"
    const val PRIORITY = "priority"

    /** MDC에 태스크 컨텍스트 설정. null 값은 설정하지 않음. */
    @JvmStatic
    fun putTaskContext(taskId: String?, queueName: String?, priority: String?) {
        if (taskId != null) MDC.put(TASK_ID, taskId)
        if (queueName != null) MDC.put(QUEUE_NAME, queueName)
        if (priority != null) MDC.put(PRIORITY, priority)
    }

    /** MDC에서 태스크 컨텍스트 제거. 스레드 풀 재사용 시 누수 방지. */
    @JvmStatic
    fun clearTaskContext() {
        MDC.remove(TASK_ID)
        MDC.remove(QUEUE_NAME)
        MDC.remove(PRIORITY)
    }

    @JvmStatic fun getTaskId(): String? = MDC.get(TASK_ID)

    @JvmStatic fun getQueueName(): String? = MDC.get(QUEUE_NAME)

    @JvmStatic fun getPriority(): String? = MDC.get(PRIORITY)
}
