package maple.expectation.infra.adapter

import maple.expectation.core.domain.model.Page
import maple.expectation.core.domain.model.PageRequest
import maple.expectation.core.port.out.OcidQueryPort
import maple.expectation.infrastructure.persistence.jpa.GameCharacterJpaRepository
import org.springframework.data.domain.PageRequest as SpringPageRequest
import org.springframework.stereotype.Component

/**
 * OcidQueryPort의 JPA 기반 구현체
 *
 * <h3>Wiring</h3>
 * module-infra의 GameCharacterJpaRepository에 위임하여 OCID 목록을 페이지 단위로 조회합니다.
 *
 * <h3>Usage</h3>
 * 배치 작업에서 대량의 OCID를 효율적으로 처리하기 위해 페이지네이션을 지원합니다.
 */
@Component
class OcidQueryAdapter(
    private val repository: GameCharacterJpaRepository
) : OcidQueryPort {

    override fun findAllOcids(pageRequest: PageRequest): Page<String> {
        val springPageable = SpringPageRequest.of(pageRequest.page, pageRequest.size)
        val springPage = repository.findAll(springPageable)
        
        return Page(
            content = springPage.content.mapNotNull { it.ocid },
            pageNumber = springPage.number,
            pageSize = springPage.size,
            totalElements = springPage.totalElements,
            hasNext = springPage.hasNext()
        )
    }
}
