package maple.expectation.core.port.out

/**
 * 알림 발송 Port 인터페이스 (ADR-005)
 *
 * <p>책임: Discord/Slack 등 외부 알림 채널로 알림 발송
 *
 * <p>구현체:
 * <ul>
 *   <li>module-infra/monitoring/alert/AlertNotificationPortAdapter
 * </ul>
 *
 * <p>참고: 기존 AlertPort는 critical 알림 전용이며,
 * 이 Port는 포맷팅된 인시던트 알림 발송을 담당
 */
interface AlertNotificationPort {

    /**
     * 알림 발송
     *
     * @param content 포맷팅된 알림 내용
     */
    fun send(content: String)

    /**
     * 인시던트 알림 메시지 포맷팅
     *
     * @param incidentId 인시던트 ID
     * @param severity 심각도 (CRIT/WARN)
     * @param signals 상위 신호 리스트 (최대 3개)
     * @param hypotheses AI 생성 가설 (최대 2개)
     * @param actions 조치 항목 (최대 2개)
     * @return 포맷팅된 메시지
     */
    fun formatIncidentMessage(
        incidentId: String,
        severity: String,
        signals: List<AnnotatedSignal>,
        hypotheses: List<String>,
        actions: List<String>,
    ): String

    /**
     * 어노테이션이 있는 신호
     *
     * @param signalName 신호 이름
     * @param signalUnit 단위
     * @param value 현재 값
     */
    data class AnnotatedSignal(
        val signalName: String,
        val signalUnit: String?,
        val value: Double,
    )
}
