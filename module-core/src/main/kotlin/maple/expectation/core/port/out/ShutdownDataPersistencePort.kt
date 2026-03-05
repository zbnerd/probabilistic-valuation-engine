package maple.expectation.core.port.out

/**
 * Shutdown 데이터 영속성 포트
 *
 * 애플리케이션 종료 시 데이터 백업 및 복구를 위한 포트 인터페이스.
 * DIP 준수: infra 구현체가 이 인터페이스를 구현.
 */
interface ShutdownDataPersistencePort {

    /**
     * Outbox 항목을 파일에 백업
     *
     * @param requestId 요청 ID
     * @param payload 페이로드 데이터
     */
    fun appendOutboxEntry(requestId: String?, payload: String?)
}
