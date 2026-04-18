package maple.expectation.infrastructure.persistence

import jakarta.persistence.Entity
import jakarta.persistence.ManyToMany
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.OneToOne
import maple.expectation.domain.nexon.NexonApiCharacterData
import maple.expectation.domain.v2.DonationHistory
import maple.expectation.domain.v2.EquipmentExpectationSummary
import maple.expectation.domain.v2.GameCharacter
import maple.expectation.domain.v2.Member
import maple.expectation.infrastructure.persistence.entity.CharacterEquipmentJpaEntity
import maple.expectation.infrastructure.persistence.entity.CharacterLikeJpaEntity
import maple.expectation.infrastructure.persistence.entity.CharacterValuationEntity
import maple.expectation.infrastructure.persistence.entity.CharacterValuationViewEntity
import maple.expectation.infrastructure.persistence.entity.GameCharacterJpaEntity
import maple.expectation.infrastructure.persistence.entity.NexonRawDataEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * JPA N+1 회귀 테스트 (#659)
 *
 * <p>CQRS + Denormalized JSONB 아키텍처에서 JPA 관계형 연관관계가
 * 도입되면 N+1 쿼리 위험이 발생합니다. 이 테스트는 다음을 검증합니다:
 *
 * <ul>
 *   <li>Entity 클래스에 @OneToMany/@ManyToMany 관계가 없는지</li>
 *   <li>현재 아키텍처 invariant: JPA Entity 간 연관관계 없음</li>
 * </ul>
 *
 * <p>현재 아키텍처는 읽기 모델로 JSONB 기반 CQRS를 사용하므로
 * JPA 엔티티 간 연관관계가 없습니다. 이 테스트는 향후 연관관계 추가 시
 * N+1 위험을 자동 감지합니다.
 */
class JpaNPlusOneRegressionTest {

    /** Active entities — CQRS read/write models, no JPA relationships expected. */
    private val activeEntities: List<Class<*>> = listOf(
        CharacterEquipmentJpaEntity::class.java,
        NexonRawDataEntity::class.java,
        CharacterLikeJpaEntity::class.java,
        CharacterValuationEntity::class.java,
        CharacterValuationViewEntity::class.java,
        GameCharacterJpaEntity::class.java,
        NexonApiCharacterData::class.java,
        Member::class.java,
        DonationHistory::class.java,
        EquipmentExpectationSummary::class.java,
    )

    /** Legacy v2 entities — may have protected relationships (@EntityGraph + LAZY). */
    private val legacyEntities: List<Class<*>> = listOf(
        GameCharacter::class.java,
    )

    private val allEntities: List<Class<*>> = activeEntities + legacyEntities

    @Test
    @DisplayName("모든 Entity가 @Entity 어노테이션을 가져야 함")
    fun `all classes are JPA entities`() {
        val nonEntities = allEntities.filter {
            !it.isAnnotationPresent(Entity::class.java)
        }
        assertTrue(nonEntities.isEmpty(), "@Entity 누락: ${nonEntities.map { it.simpleName }}")
    }

    @Test
    @DisplayName("Entity에 @OneToMany/@ManyToMany 관계가 없어야 함")
    fun `no collection-valued relationships`() {
        val violations = allEntities.flatMap { clazz ->
            clazz.declaredFields.filter { field ->
                field.isAnnotationPresent(OneToMany::class.java) ||
                    field.isAnnotationPresent(ManyToMany::class.java)
            }.map { "${clazz.simpleName}.${it.name}" }
        }

        assertTrue(
            violations.isEmpty(),
            "N+1 위험: @OneToMany/@ManyToMany 발견: $violations\n" +
                "연관관계 추가 시 @BatchSize 또는 @EntityGraph를 반드시 적용하세요.",
        )
    }

    @Test
    @DisplayName("활성 Entity에 JPA 연관관계가 없어야 함 (CQRS invariant)")
    fun `active entities have no JPA relationships`() {
        val relationshipAnnotations = setOf(
            OneToMany::class.java, ManyToOne::class.java,
            ManyToMany::class.java, OneToOne::class.java,
        )

        val violations = activeEntities.flatMap { clazz ->
            clazz.declaredFields.filter { field ->
                field.annotations.any { ann -> relationshipAnnotations.contains(ann.annotationClass.java) }
            }.map { "${clazz.simpleName}.${it.name}" }
        }

        assertEquals(
            0, violations.size,
            "활성 Entity는 CQRS + JSONB로 JPA 연관관계가 없어야 합니다.\n" +
                "발견: $violations\n" +
                "연관관계 추가 시 이 테스트를 업데이트하고 @BatchSize/@EntityGraph를 적용하세요.",
        )
    }
}
