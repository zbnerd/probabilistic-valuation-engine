package maple.expectation.infrastructure.aop.annotation

/**
 * 워커 태스크 전체 처리 시간 측정 어노테이션
 *
 * AOP가 전체 태스크 처리 시간을 측정하여 Micrometer Timer와 structured log에 기록합니다.
 * MDC에서 taskId, queueName, priority를 읽어 메트릭 태그와 로그에 포함합니다.
 *
 * ## Metric
 * - `expectation.worker.task.duration{queue, priority, result}`
 *
 * ## 주의
 * - 반드시 public 메서드에 적용 (Spring AOP 프록시 필요)
 * - 같은 클래스 내 self-invocation 시 AOP 미동작 → 별도 Bean의 메서드에 적용
 *
 * @property value 태스크 유형 식별자 (로그용)
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class TimedTask(
    val value: String,
)
