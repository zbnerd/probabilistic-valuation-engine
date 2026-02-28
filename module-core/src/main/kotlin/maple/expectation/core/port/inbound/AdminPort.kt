package maple.expectation.core.port.inbound

/**
 * Admin 권한 관리 Port 인터페이스 (ADR-005)
 *
 * <p>책임: Admin 권한 조회, 추가, 제거
 *
 * <p>구현체:
 * <ul>
 *   <li>module-app/adapter/in/AdminPortAdapter - AdminService에 위임
 * </ul>
 */
interface AdminPort {

    /**
     * fingerprint가 Admin인지 확인
     *
     * @param fingerprint 확인할 fingerprint
     * @return true: Admin, false: 일반 사용자
     */
    fun isAdmin(fingerprint: String): Boolean

    /**
     * 새 Admin 추가
     *
     * @param fingerprint 추가할 Admin의 fingerprint
     */
    fun addAdmin(fingerprint: String)

    /**
     * Admin 제거
     *
     * @param fingerprint 제거할 Admin의 fingerprint
     * @return true: 제거 성공, false: Bootstrap Admin이거나 존재하지 않음
     */
    fun removeAdmin(fingerprint: String): Boolean

    /**
     * 전체 Admin 목록 조회
     *
     * @return Admin fingerprint Set
     */
    fun getAllAdmins(): Set<String>
}
