package maple.expectation.infrastructure.adapter

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import jakarta.annotation.PostConstruct
import maple.expectation.core.port.out.CharacterOcidPort
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.persistence.jpa.GameCharacterJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * Character OCID Resolution Adapter (ADR-005, ADR-030)
 *
 * <p>GameCharacterJpaRepository에 위임하여 OCID 조회 기능을 제공합니다.
 *
 * <h3>캐싱 전략</h3>
 * <ul>
 *   <li>단일 OCID 조회: Caffeine 캐시 (5분 TTL)
 *   <li>전체 OCID 목록: Caffeine 캐시 (1분 TTL, 변경 빈도 고려)
 * </ul>
 *
 * <h3>P1 N+1 Query 최적화</h3>
 * <p>JwtAuthenticationFilter에서 매 요청마다 DB를 조회하는 것을 방지하기 위해
 * Caffeine 캐시를 도입하여 DB 부하를 줄입니다.
 */
@Component
class CharacterOcidAdapter(
    private val jpaRepository: GameCharacterJpaRepository,
    private val executor: LogicExecutor,
) : CharacterOcidPort {

    private val singleOcidCache: Cache<String, String?> = Caffeine.newBuilder()
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .maximumSize(1000)
        .build()

    private val allOcidsCache: Cache<Unit, Map<String, String>> = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .maximumSize(1)
        .build()

    @PostConstruct
    fun init() {
        log.info("[CharacterOcidAdapter] Initialized with Caffeine cache (single: 5min, all: 1min)")
    }

    override fun resolveOcid(userIgn: String): String? {
        return singleOcidCache.get(userIgn) {
            executor.execute(
                { resolveFromDb(userIgn) },
                TaskContext.of("CharacterOcidAdapter", "ResolveOcid", userIgn),
            )
        }
    }

    override fun resolveOcids(userIgns: Set<String>): Map<String, String> {
        if (userIgns.isEmpty()) {
            return emptyMap()
        }

        return executor.execute(
            {
                val entities = jpaRepository.findAllByUserIgnIn(userIgns.toList())
                entities
                    .filter { it.userIgn != null && it.ocid != null }
                    .associateBy({ it.userIgn!! }, { it.ocid!! })
                    .filterKeys { it in userIgns }
            },
            TaskContext.of("CharacterOcidAdapter", "ResolveOcids", "count=${userIgns.size}"),
        )
    }

    override fun resolveAllOcids(): Map<String, String> {
        return allOcidsCache.get(Unit) {
            executor.execute(
                { loadAllOcidsFromDb() },
                TaskContext.of("CharacterOcidAdapter", "ResolveAllOcids"),
            )
        }
    }

    private fun resolveFromDb(userIgn: String): String? {
        val entity = jpaRepository.findByUserIgn(userIgn)
        return entity?.ocid
    }

    private fun loadAllOcidsFromDb(): Map<String, String> {
        val entities = jpaRepository.findAll()
        val result = entities
            .filter { it.userIgn != null && it.ocid != null }
            .associateBy({ it.userIgn!! }, { it.ocid!! })

        log.debug("[CharacterOcidAdapter] Loaded {} characters from DB", result.size)

        return result
    }

    companion object {
        private val log = LoggerFactory.getLogger(CharacterOcidAdapter::class.java)
    }
}
