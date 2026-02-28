package maple.expectation.core.port.out

/**
 * Queue Writer Port - 배치 작업이 큐에 작업을 추가하는 인터페이스
 *
 * <h3>역할</h3>
 *
 * <p>배치 작업을 큐에 추가하기 위한 포트 인터페이스입니다. 우선순위별로 작업을 추가할 수 있으며,
 * 백프레셔(Backpressure)를 통해 큐가 가득 찼을 때의 처리를 지원합니다.
 *
 * <h3>DIP 준수</h3>
 *
 * <p>인프라스트럭처 모듈이 이 추상화에 의존하도록 하여, 비즈니스 로직이 구체적인 Redis API에
 * 의존하지 않도록 합니다.
 *
 * <h3>구현체</h3>
 *
 * <ul>
 *   <li>module-infra/adapter/QueueWriterAdapter - Redis 기반 구현
 * </ul>
 */
interface QueueWriterPort {

    /**
     * 큐에 저우선순위 작업 추가
     *
     * <p>일반적인 배치 작업을 큐에 추가합니다. 큐가 가득 찬 경우 백프레셔를 위해 false를 반환합니다.
     *
     * @param userIgn 사용자 IGN
     * @return true: 추가 성공, false: 큐 full (백프레셔)
     */
    fun addLowPriorityTask(userIgn: String): Boolean

    /**
     * 큐에 고우선순위 작업 추가
     *
     * <p>강제 재계산이나 우선 처리가 필요한 작업을 큐에 추가합니다.
     *
     * @param userIgn 사용자 IGN
     * @param forceRecalculation 강제 재계산 여부
     * @return true: 추가 성공, false: 큐 full
     */
    fun addHighPriorityTask(userIgn: String, forceRecalculation: Boolean): Boolean
}
