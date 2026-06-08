package maple.expectation.infrastructure.persistence.repository

import com.fasterxml.jackson.dataformat.csv.CsvMapper
import com.fasterxml.jackson.dataformat.csv.CsvSchema
import jakarta.annotation.PostConstruct
import maple.expectation.core.domain.model.CubeType
import maple.expectation.error.exception.CubeDataInitializationException
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.persistence.entity.CubeProbability
import maple.expectation.infrastructure.persistence.repository.CubeProbabilityRepository
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Repository

/**
 * 큐브 확률 데이터 CSV 기반 Repository 구현체 (V1)
 *
 * <p>애플리케이션 시작 시 CSV 파일을 읽어 메모리에 캐싱합니다.
 * CubeType 구분하여 캐시 키를 생성합니다.
 */
@Repository("cubeProbabilityRepositoryV1")
class CubeProbabilityRepositoryImpl(
    private val executor: LogicExecutor,
) : CubeProbabilityRepository {

    companion object {
        private val log = LoggerFactory.getLogger(CubeProbabilityRepositoryImpl::class.java)
    }

    // 🔑 캐시 키에 CubeType이 포함되어야 합니다. (예: BLACK_200_모자_레전드리_1)
    private val probabilityCache: MutableMap<String, MutableList<CubeProbability>> = mutableMapOf()

    @PostConstruct
    fun init() {
        log.info("[v1] CSV 큐브 확률 데이터 로딩 시작... (CubeType 구분 적용)")

        val resource = ClassPathResource("data/cube_probability.csv")
        if (!resource.exists()) {
            throw CubeDataInitializationException("필수 데이터 파일이 없습니다: data/cube_probability.csv")
        }

        executor.executeVoid({
            resource.inputStream.use { inputStream ->
                val mapper = CsvMapper()
                val schema = CsvSchema.emptySchema().withHeader()

                val it = mapper.readerFor(CubeProbability::class.java)
                    .with(schema)
                    .readValues<CubeProbability>(inputStream)

                var count = 0
                while (it.hasNext()) {
                    val p = it.next()

                    // 💡 핵심: 큐브 종류(Black, Red 등)까지 포함하여 키 생성
                    val key = generateKey(p.cubeType, p.level, p.part, p.grade, p.slot)

                    probabilityCache.computeIfAbsent(key) { mutableListOf() }.add(p)
                    count++
                }

                if (count == 0) {
                    throw CubeDataInitializationException("CSV 파일 데이터가 비어있습니다.")
                }

                log.info("[v1] 로딩 완료! 총 {}건의 데이터를 적재했습니다. (Key 개수: {})", count, probabilityCache.size)
            }
        }, TaskContext.of("CubeProbability", "InitCsvLoad"))
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

    override fun findAll(): List<CubeProbability> = probabilityCache.values.flatten()

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
        // CSV 기반 구현: 고정 버전
        // 향후: DB 기반 시 실제 버전 조회
        return "csv-v1.0"
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
