package maple.expectation.core.port.out

/**
 * 시스템 메트릭 수집 Port 인터페이스 (ADR-005)
 *
 * <p>책임: 시스템 상태 메트릭 수집
 *
 * <p>구현체:
 * <ul>
 *   <li>module-app/monitoring/context/SystemContextProvider
 * </ul>
 */
interface SystemMetricsPort {

    /**
     * 전체 시스템 메트릭 수집
     *
     * @return 카테고리별 메트릭 맵
     */
    fun collectAllMetrics(): Map<*, Map<String, Any>>
}
