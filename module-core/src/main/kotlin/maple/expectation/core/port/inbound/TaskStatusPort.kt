package maple.expectation.core.port.inbound

/**
 * Task 상태 조회 Port (ADR-355)
 *
 * <p>V5 클라이언트가 비동기 계산 완료 여부를 polling.
 * PostgreSQL CharacterView을 source of truth로 사용.
 */
interface TaskStatusPort {

    /**
     * Task 상태 조회
     *
     * @param userIgn 캐릭터 IGN
     * @param taskId PGMQ message ID
     * @return Task 상태
     */
    fun getStatus(userIgn: String, taskId: String): TaskStatus
}
