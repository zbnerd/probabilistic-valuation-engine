package maple.expectation.core.port.out

import java.util.concurrent.CompletableFuture

/**
 * Nexon Data Collector Port - Nexon API 데이터 수집 인터페이스
 *
 * <h3>역할</h3>
 *
 * <p>Nexon Open API에서 캐릭터 데이터를 수집하고 큐에 게시하는 포트 인터페이스입니다.
 * 스케줄러가 주기적으로 호출하여 데이터를 수집할 때 사용합니다.
 *
 * <h3>DIP 준수</h3>
 *
 * <p>스케줄러 모듈이 이 추상화에 의존하도록 하여, 비즈니스 로직이 구체적인 NexonDataCollector 구현에
 * 의존하지 않도록 합니다.
 *
 * <h3>구현체</h3>
 *
 * <ul>
 *   <li>module-infra/infrastructure/external/impl/NexonDataCollectorImpl - WebClient 기반 구현
 * </ul>
 */
interface NexonDataCollectorPort {

    /**
     * Nexon API에서 캐릭터 데이터를 가져와서 큐에 게시
     *
     * <p><strong>Workflow:</strong>
     *
     * <ol>
     *   <li>Nexon API 호출 (HTTP GET)
     *   <li>JSON 응답을 파싱
     *   <li>IntegrationEvent로 래핑
     *   <li>큐에 게시 (fire-and-forget)
     *   <li>CompletableFuture로 결과 반환
     * </ol>
     *
     * <p><strong>Reactive Features:</strong>
     *
     * <ul>
     *   <li>Timeout: 5 seconds (hangining request 방지)
     *   <li>Retry: 5xx 에러 시 최대 2회 재시도
     *   <li>Fire-and-forget publish: 이벤트 게시가 응답을 block하지 않음
     *   <li>Eager execution: 즉시 subscribe하여 실행 보장
     * </ul>
     *
     * @param ocid 캐릭터 OCID
     * @return API 호출과 게시 완료 시 complete되는 CompletableFuture
     */
    fun fetchAndPublish(ocid: String): CompletableFuture<Void>
}
