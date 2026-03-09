package maple.expectation.infrastructure.persistence.worker

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import maple.expectation.core.domain.model.character.CharacterId
import maple.expectation.core.domain.model.equipment.CharacterEquipment
import maple.expectation.core.domain.model.equipment.EquipmentData
import maple.expectation.core.port.out.PersistenceTrackerStrategy
import maple.expectation.domain.repository.CharacterEquipmentRepository
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator
import maple.expectation.infrastructure.external.dto.v2.EquipmentResponse
import maple.expectation.util.StringMaskingUtils
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Equipment DB 계층 전담 Worker (SRP 준수)
 *
 * 책임:
 * - DB 조회: 15분 TTL 체크 포함
 * - DB 저장: 비동기 + Graceful Shutdown 지원
 *
 * 데이터 소스 계층 (L1 → L2 → DB → API)
 * DB는 L2 캐시 뒤, Nexon API 앞에 위치하여 API 호출 최소화
 */
@Component
class EquipmentDbWorker(
    private val repository: CharacterEquipmentRepository,
    private val objectMapper: ObjectMapper,
    private val persistenceTracker: PersistenceTrackerStrategy,
    private val executor: LogicExecutor,
) {
    /**
     * 비동기 저장 로직
     * try-catch 대신 executeOrCatch를 사용하여 Future의 상태를 결정합니다.
     */
    @Async
    @Transactional("transactionManager", propagation = Propagation.REQUIRES_NEW)
    fun persist(ocid: String, response: EquipmentResponse): CompletableFuture<Void> {
        val future = CompletableFuture<Void>()
        val context = TaskContext.of("EquipmentWorker", "AsyncPersist", ocid)

        // Graceful Shutdown 지원: 작업 추적 등록
        persistenceTracker.trackOperation(ocid, future)

        return executor.executeOrCatch(
            {
                performSave(ocid, response, context)
                log.debug("💾 [Async DB Save Success] ocid: {}", ocid)
                future.complete(null)
                future
            },
            { e ->
                log.error("❌ [Async DB Save Error] ocid: {} | 사유: {}", ocid, e.message)
                future.completeExceptionally(e)
                future
            },
            context,
        )
    }

    /** 헬퍼: 실제 저장 로직 (직렬화 및 DB 반영) */
    private fun performSave(ocid: String, response: EquipmentResponse, context: TaskContext) {
        // Jackson 직렬화 시 발생하는 체크 예외를 도메인 예외로 세탁
        val json = executor.executeWithTranslation(
            { objectMapper.writeValueAsString(response) },
            ExceptionTranslator.forJson(),
            context,
        )

        var entity = repository.findById(CharacterId.of(ocid))
        if (entity == null) {
            entity = CharacterEquipment.createEmpty(CharacterId.of(ocid))
        }

        val updated = entity.withUpdatedData(EquipmentData.of(json))
        repository.save(updated)
    }

    // ==================== DB 조회 API (SRP: DB 계층 전담) ====================

    /**
     * 유효한 DB 데이터 조회 (Rich Domain Model)
     *
     * CharacterEquipment.isFresh(Duration)를 사용하여 TTL 체크
     *
     * @param ocid 캐릭터 OCID
     * @return 유효한 JSON 데이터 (없거나 만료되면 empty)
     */
    @Transactional("transactionManager", readOnly = true)
    fun findValidJson(ocid: String): Optional<String> {
        return executor.execute(
            {
                val equipment = repository.findById(CharacterId.of(ocid))

                if (equipment != null && equipment.isFresh(DB_TTL)) {
                    log.debug("[EquipmentDb] DB HIT (TTL valid): ocid={}", StringMaskingUtils.maskOcid(ocid))
                    if (equipment.hasData()) {
                        return@execute Optional.ofNullable(equipment.jsonContent())
                    }
                } else {
                    log.debug("[EquipmentDb] DB MISS or TTL expired: ocid={}", StringMaskingUtils.maskOcid(ocid))
                }

                Optional.empty()
            },
            TaskContext.of("EquipmentDb", "FindValid", ocid),
        )
    }

    // ==================== Raw JSON 저장 API (Expectation 경로용) ====================

    /**
     * Raw JSON 비동기 저장 (Expectation 경로 전용)
     *
     * EquipmentResponse 직렬화 없이 이미 직렬화된 JSON을 저장
     * Nexon API 호출 후 DB에 저장하여 다음 요청에서 API 호출 최소화
     */
    @Async
    @Transactional("transactionManager", propagation = Propagation.REQUIRES_NEW)
    fun persistRawJson(ocid: String, json: String): CompletableFuture<Void> {
        val future = CompletableFuture<Void>()
        val context = TaskContext.of("EquipmentDb", "PersistRaw", ocid)

        persistenceTracker.trackOperation(ocid, future)

        return executor.executeOrCatch(
            {
                performRawSave(ocid, json)
                log.debug("💾 [DB Save] Raw JSON saved: ocid={}", StringMaskingUtils.maskOcid(ocid))
                future.complete(null)
                future
            },
            { e ->
                log.error("❌ [DB Save Error] ocid={} | err={}", StringMaskingUtils.maskOcid(ocid), e.message)
                future.completeExceptionally(e)
                future
            },
            context,
        )
    }

    /** 헬퍼: Raw JSON 저장 로직 */
    private fun performRawSave(ocid: String, json: String) {
        var entity = repository.findById(CharacterId.of(ocid))
        if (entity == null) {
            entity = CharacterEquipment.createEmpty(CharacterId.of(ocid))
        }

        val updated = entity.withUpdatedData(json)
        repository.save(updated)
    }

    companion object {
        private val log = LoggerFactory.getLogger(EquipmentDbWorker::class.java)
        private val DB_TTL = Duration.ofMinutes(15)
    }
}
