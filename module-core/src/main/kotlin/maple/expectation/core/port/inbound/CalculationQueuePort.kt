package maple.expectation.core.port.inbound

/**
 * V5 CQRS Command Side Port (ADR-005)
 *
 * <p>책임: 우선순위 큐에 계산 작업 추가
 *
 * <p>구현체:
 * <ul>
 *   <li>module-app/adapter/in/CalculationQueuePortAdapter - PriorityCalculationQueue에 위임
 * </ul>
 */
interface CalculationQueuePort {

    /**
     * 고우선순위 계산 작업 추가
     *
     * @param userIgn 캐릭터 IGN
     * @param forceRecalculation 강제 재계산 여부
     * @return true: 큐에 추가됨, false: 큐 full (백프레셔)
     */
    fun offerHighPriority(userIgn: String, forceRecalculation: Boolean): Boolean
}
