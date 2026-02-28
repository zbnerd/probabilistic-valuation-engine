package maple.expectation.core.port.out

import maple.expectation.core.domain.model.Page
import maple.expectation.core.domain.model.PageRequest

/**
 * OCID Query Port - 배치 작업이 OCID를 조회하는 인터페이스
 *
 * <p>이 인터페이스는 페이지네이션을 통해 OCID 목록을 조회하는 기능을 제공합니다.
 * 주로 배치 작업에서 대량의 OCID를 처리할 때 사용됩니다.
 *
 * <h3>Usage</h3>
 *
 * <p>module-infra 어댑터에 의해 구현되어 underlying data store(DB 등)에서
 * OCID 목록을 페이지 단위로 조회합니다.
 *
 * @see maple.expectation.core.domain.model.Page
 * @see maple.expectation.core.domain.model.PageRequest
 */
interface OcidQueryPort {

    /**
     * 페이지네이션으로 모든 OCID 조회
     *
     * <p>배치 작업에서 대량의 OCID를 효율적으로 처리하기 위해
     * 페이지 단위로 조회합니다.
     *
     * @param pageRequest 페이지네이션 요청 정보 (page, size)
     * @return OCID 목록이 담긴 페이지
     */
    fun findAllOcids(pageRequest: PageRequest): Page<String>
}
