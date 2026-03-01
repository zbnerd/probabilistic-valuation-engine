package maple.expectation.core.port.inbound

/**
 * 도네이션(커피 후원) Port 인터페이스 (ADR-005)
 *
 * <p>책임: 게스트가 Admin(개발자)에게 커피를 사주는 기능
 *
 * <p>구현체:
 * <ul>
 *   <li>module-app/adapter/in/DonationPortAdapter - DonationService에 위임
 * </ul>
 */
interface DonationPort {

    /**
     * Admin(개발자)에게 커피 보내기
     *
     * @param command 도네이션 명령
     * @throws IllegalArgumentException 유효하지 않은 Admin fingerprint
     * @throws IllegalStateException 잔액 부족
     */
    fun sendCoffee(command: DonationCommand)

    /**
     * Admin 권한 확인
     *
     * @param fingerprint 확인할 fingerprint
     * @return true: Admin, false: 일반 사용자
     */
    fun isAdmin(fingerprint: String): Boolean
}
