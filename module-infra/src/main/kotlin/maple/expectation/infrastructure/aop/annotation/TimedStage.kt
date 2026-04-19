package maple.expectation.infrastructure.aop.annotation

/**
 * 워커 스테이지별 처리 시간 측정 어노테이션
 *
 * 태스크 내 개별 스테이지(fetch, parse, calculate, persist, view-upsert, event-publish)의
 * 처리 시간을 측정합니다. threshold 초과 시 WARN 로그를 출력합니다.
 *
 * ## Metric
 * - `expectation.worker.stage.duration{stage, result}`
 *
 * ## Threshold
 * - `warnThresholdMs` > 0 이고 실제 소요 시간이 초과하면 WARN 로그 출력
 * - 0 (기본값)이면 threshold 체크 없음
 *
 * ## 주의
 * - 반드시 public 메서드에 적용 (Spring AOP 프록시 필요)
 * - 같은 클래스 내 self-invocation 시 AOP 미동작 → 별도 Bean의 메서드에 적용
 *
 * @property value 스테이지 이름 (fetch, parse, calculate, persist, view_upsert, event_publish)
 * @property warnThresholdMs 경고 임계값 (ms). 0이면 체크 안함
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class TimedStage(
    val value: String,
    val warnThresholdMs: Long = 0,
)
