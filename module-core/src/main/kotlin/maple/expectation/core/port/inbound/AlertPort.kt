package maple.expectation.core.port.inbound

/**
 * 알림 Port 인터페이스 (ADR-005)
 *
 * <p>책임: critical 알림 전송
 *
 * <p>구현체:
 * <ul>
 *   <li>module-app/adapter/in/AlertPortAdapter - DiscordAlertService에 위임
 * </ul>
 */
interface AlertPort {

    /**
     * Critical 알림 전송
     *
     * @param title 알림 제목
     * @param description 알림 설명
     * @param error 관련 예외
     */
    fun sendCriticalAlert(title: String, description: String, error: Throwable)
}
