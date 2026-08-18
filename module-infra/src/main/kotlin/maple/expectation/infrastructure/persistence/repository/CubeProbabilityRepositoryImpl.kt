package maple.expectation.infrastructure.persistence.repository

import jakarta.annotation.PostConstruct
import maple.expectation.core.calculation.probability.ProbabilityTableSnapshot
import maple.expectation.core.domain.model.CubeType
import maple.expectation.error.exception.CubeDataInitializationException
import maple.expectation.infrastructure.persistence.entity.CubeProbability
import maple.expectation.infrastructure.persistence.repository.CubeProbabilityRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository

/**
 * 큐브 확률 데이터 CSV 기반 Repository 구현체 (V1)
 *
 * <p>Projects the immutable core probability snapshot into the legacy entity API.
 * Cache keys retain the historical CubeType-aware shape.
 */
@Repository("cubeProbabilityRepositoryV1")
class CubeProbabilityRepositoryImpl(
    private val snapshot: ProbabilityTableSnapshot,
) : CubeProbabilityRepository {

    companion object {
        private val log = LoggerFactory.getLogger(CubeProbabilityRepositoryImpl::class.java)
    }

    private val allProbabilities: List<CubeProbability> = snapshot.entries().map { (key, row) ->
        CubeProbability(
            cubeType = key.cubeType,
            optionName = row.optionName,
            rate = row.rate,
            slot = key.slot,
            grade = key.grade,
            level = key.level,
            part = key.part,
        )
    }
    private val probabilityCache: Map<String, List<CubeProbability>> = allProbabilities.groupBy { probability ->
        generateKey(
            probability.cubeType,
            probability.level,
            probability.part,
            probability.grade,
            probability.slot,
        )
    }

    @PostConstruct
    fun init() {
        if (allProbabilities.isEmpty()) {
            throw CubeDataInitializationException("CSV 파일 데이터가 비어있습니다.")
        }
        log.info(
            "[v1] Core snapshot projection ready: rows={}, keys={}, version={}",
            allProbabilities.size,
            probabilityCache.size,
            snapshot.version.logical,
        )
    }

    /**
     * ✅ 수정: 큐브 종류(type)를 파라미터로 받아 정확한 확률 리스트를 반환합니다.
     */
    override fun findProbabilities(
        type: CubeType,
        level: Int,
        part: String,
        grade: String,
        slot: Int,
    ): List<CubeProbability> {
        val key = generateKey(type, level, part, grade, slot)
        return probabilityCache[key] ?: emptyList()
    }

    override fun findAll(): List<CubeProbability> = allProbabilities

    override fun findProbabilitiesByVersion(
        type: CubeType,
        level: Int,
        part: String,
        grade: String,
        slot: Int,
        tableVersion: String,
    ): List<CubeProbability> {
        // 현재 구현: 버전 무시 (CSV는 시작 시 로딩되어 고정)
        // 향후: tableVersion과 currentVersion 비교 후 불일치 시 예외
        return findProbabilities(type, level, part, grade, slot)
    }

    override fun getCurrentTableVersion(): String {
        return snapshot.version.logical
    }

    /**
     * ✅ 수정: Key 생성 로직에 type.name() 추가
     */
    private fun generateKey(
        type: CubeType,
        level: Int,
        part: String,
        grade: String,
        slot: Int,
    ): String = "${type.name}_${level}_${part}_${grade}_$slot"
}
