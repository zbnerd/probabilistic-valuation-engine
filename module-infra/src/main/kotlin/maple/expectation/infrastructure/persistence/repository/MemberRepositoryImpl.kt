package maple.expectation.infrastructure.persistence.repository

import maple.expectation.domain.repository.MemberRepository
import maple.expectation.domain.v2.Member
import maple.expectation.infrastructure.persistence.jpa.MemberJpaRepository
import org.springframework.stereotype.Repository

/**
 * MemberRepository Implementation
 *
 * <p>Implements the domain {@link MemberRepository} interface using Spring Data JPA.
 *
 * <p>This class bridges the domain layer (MemberRepository interface) with the infrastructure
 * layer (MemberJpaRepository) following the Ports and Adapters pattern.
 *
 * @see MemberRepository
 * @see MemberJpaRepository
 */
@Repository
open class MemberRepositoryImpl(
    private val jpaRepository: MemberJpaRepository
) : MemberRepository {

    override fun findByUuid(uuid: String): Member? {
        return jpaRepository.findByUuid(uuid).orElse(null)
    }

    override fun findById(id: Long?): Member? {
        if (id == null) return null
        return jpaRepository.findById(id).orElse(null)
    }

    override fun save(member: Member): Member {
        return jpaRepository.save(member)
    }

    override fun deleteByUuid(uuid: String) {
        jpaRepository.deleteByUuid(uuid)
    }

    override fun existsByUuid(uuid: String): Boolean {
        return jpaRepository.existsByUuid(uuid)
    }

    override fun findOrCreateGuest(uuid: String, initialPoint: Long): Member {
        return findByUuid(uuid) ?: run {
            // Use reflection to access private constructor (uuid, initialPoint)
            val constructor = Member::class.java.getDeclaredConstructor(String::class.java, Long::class.java)
            constructor.isAccessible = true
            val guest = constructor.newInstance(uuid, initialPoint)
            save(guest)
        }
    }

    override fun increasePointByUuid(uuid: String, amount: Long): Int {
        return jpaRepository.increasePointByUuid(uuid, amount)
    }
}
