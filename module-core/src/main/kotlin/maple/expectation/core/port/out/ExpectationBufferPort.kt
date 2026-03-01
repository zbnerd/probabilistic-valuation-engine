package maple.expectation.core.port.out

/**
 * Expectation Buffer Port 인터페이스 (ADR-005)
 *
 * <p>책임: Write-Behind 버퍼 관리
 *
 * <p>구현체:
 * <ul>
 *   <li>module-app/service/v4/buffer/ExpectationWriteBackBuffer
 * </ul>
 */
interface ExpectationBufferPort {

    /**
     * 종료 중인지 확인
     */
    fun isShuttingDown(): Boolean

    /**
     * 버퍼가 비어있는지 확인
     */
    fun isEmpty(): Boolean

    /**
     * 대기 중인 작업 수
     */
    fun getPendingCount(): Int
}
