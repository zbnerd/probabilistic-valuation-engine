package maple.expectation.core.port.out.like

import maple.expectation.core.dto.like.FetchResult

/**
 * 보상 트랜잭션 명령 인터페이스 (Command Pattern)
 *
 * 금융수준 안전 설계:
 * - save(): 작업 전 상태 저장 (FetchResult)
 * - compensate(): 실패 시 원복 (임시 키 → 원본 키)
 * - commit(): 성공 시 정리 (임시 키 삭제)
 *
 * 사용 패턴:
 * ```kotlin
 * val cmd: CompensationCommand = RedisCompensationCommand(...)
 *
 * executor.executeWithFinally(
 *     {
 *         val result = strategy.fetchAndMove(sourceKey, tempKey)
 *         cmd.save(result)
 *         processData(result)  // DB 저장 등
 *         cmd.commit()
 *     },
 *     {
 *         if (cmd.isPending()) {
 *             cmd.compensate()  // 실패 시 복구
 *         }
 *     },
 *     context
 * )
 * ```
 *
 * @since 2.0.0
 */
interface CompensationCommand {

    /**
     * 작업 전 상태 저장
     *
     * @param result fetch 결과 (복구에 필요한 데이터)
     */
    fun save(result: FetchResult)

    /**
     * 실패 시 보상 트랜잭션 실행
     *
     * 임시 키 데이터를 원본 키로 복원
     */
    fun compensate()

    /**
     * 성공 시 커밋 (정리)
     *
     * 임시 키 삭제, 상태 초기화
     */
    fun commit()

    /**
     * 보상이 필요한 상태인지 확인
     *
     * @return true: save() 호출됨 + commit() 미호출 → 보상 필요
     */
    fun isPending(): Boolean
}
