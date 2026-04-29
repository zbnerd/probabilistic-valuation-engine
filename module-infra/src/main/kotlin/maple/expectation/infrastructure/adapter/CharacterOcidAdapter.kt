package maple.expectation.infrastructure.adapter

import jakarta.annotation.PostConstruct
import maple.expectation.core.port.out.CharacterOcidPort
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.persistence.jpa.GameCharacterJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Character OCID Resolution Adapter (ADR-005, ADR-030)
 *
 * <p>GameCharacterJpaRepository에 위임하여 OCID 조회 기능을 제공합니다.
 *
 * <h3>캐싱 전략</h3>
 * <p>Spring Cache(@Cacheable)를 통해 TieredCacheManager(L1 Caffeine + L2 PostgreSQL)에 위임합니다.
 * 이전에 내부 Caffeine 캐시를 직접 관리하던 것을 Spring Cache 추상화로 통일하여
 * OcidResolutionService와 캐시 일관성을 보장합니다.
 *
 * <h3>P0 이중 캐싱 해소</h3>
 * <p>OcidResolutionService의 @Cacheable("ocidCache")와 동일한 캐시 이름을 사용하여
 * 단일 캐시 소스를 보장합니다.
 */
@Component
class CharacterOcidAdapter(
    private val jpaRepository: GameCharacterJpaRepository,
    private val executor: LogicExecutor,
) : CharacterOcidPort {

    @PostConstruct
    fun init() {
        log.info("[CharacterOcidAdapter] Initialized with Spring Cache (ocidCache, allOcidsCache)")
    }

    @Cacheable(value = ["ocidCache"], key = "#userIgn", unless = "#result == null")
    override fun resolveOcid(userIgn: String): String? = executor.execute(
        { resolveFromDb(userIgn) },
        TaskContext.of("CharacterOcidAdapter", "ResolveOcid", userIgn),
    )

    override fun resolveOcids(userIgns: Set<String>): Map<String, String> {
        if (userIgns.isEmpty()) {
            return emptyMap()
        }

        return executor.execute(
            {
                val entities = jpaRepository.findAllByUserIgnIn(userIgns.toList())
                entities
                    .filter { it.userIgn != null && it.ocid != null }
                    .associate { requireNotNull(it.userIgn) to requireNotNull(it.ocid) }
                    .filterKeys { it in userIgns }
            },
            TaskContext.of("CharacterOcidAdapter", "ResolveOcids", "count=${userIgns.size}"),
        )
    }

    @Cacheable(value = ["allOcidsCache"], key = "'all'")
    override fun resolveAllOcids(): Map<String, String> = executor.execute(
        { loadAllOcidsFromDb() },
        TaskContext.of("CharacterOcidAdapter", "ResolveAllOcids"),
    )

    @Cacheable(value = ["fingerprintOcidsCache"], key = "#fingerprint", unless = "#result.isEmpty()")
    override fun resolveOcidsByFingerprint(fingerprint: String): Set<String> {
        require(fingerprint.isNotBlank()) { "fingerprint must not be blank" }
        return executor.execute(
            { jpaRepository.findAllByFingerprint(fingerprint).mapNotNull { it.ocid }.toSet() },
            TaskContext.of("CharacterOcidAdapter", "ResolveOcidsByFingerprint", fingerprint),
        )
    }

    @CacheEvict(value = ["fingerprintOcidsCache"], key = "#fingerprint")
    @Transactional("transactionManager")
    override fun updateFingerprint(ocid: String, fingerprint: String, accountId: String): Int = executor.execute(
        { jpaRepository.updateFingerprintByOcid(ocid, fingerprint, accountId) },
        TaskContext.of("CharacterOcidAdapter", "UpdateFingerprint", ocid),
    )

    private fun resolveFromDb(userIgn: String): String? {
        val entity = jpaRepository.findByUserIgn(userIgn)
        return entity?.ocid
    }

    /**
     * Full load of all OCIDs from database for cache warming.
     *
     * This is an intentional full-table scan executed at startup to warm the L2 cache.
     * Results are cached via @Cacheable("allOcidsCache") on resolveAllOcids().
     * Subsequent calls hit the cache instead of hitting the database.
     */
    private fun loadAllOcidsFromDb(): Map<String, String> {
        val entities = jpaRepository.findAll()
        return entities
            .filter { it.userIgn != null && it.ocid != null }
            .associate { requireNotNull(it.userIgn) to requireNotNull(it.ocid) }
    }

    companion object {
        private val log = LoggerFactory.getLogger(CharacterOcidAdapter::class.java)
    }
}
