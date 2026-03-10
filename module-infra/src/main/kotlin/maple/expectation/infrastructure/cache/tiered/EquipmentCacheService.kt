package maple.expectation.infrastructure.cache.tiered

import java.util.Optional
import maple.expectation.error.exception.CachePersistenceException
import maple.expectation.error.exception.base.BaseException
import maple.expectation.infrastructure.cache.port.EquipmentCache
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.external.dto.v2.EquipmentResponse
import maple.expectation.infrastructure.persistence.worker.EquipmentDbWorker
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.cache.CacheManager
import org.springframework.stereotype.Service

/**
 * Equipment 캐시 서비스 (Issue #24: AbstractTieredCacheService 리팩토링)
 *
 * P1-4: Cache 필드 캐싱
 * AbstractTieredCacheService를 상속받아 L1→L2→Warm-up 패턴의 중복을 제거했습니다.
 *
 * 추가 기능:
 * - 비동기 DB 저장 (EquipmentDbWorker)
 * - Negative 캐시 체크
 */
@Service
class EquipmentCacheService(
    cacheManager: CacheManager,
    @Qualifier("expectationL1CacheManager") l1CacheManager: CacheManager,
    private val dbWorker: EquipmentDbWorker,
    executor: LogicExecutor,
) : AbstractTieredCacheService<EquipmentResponse>(CACHE_NAME, cacheManager, l1CacheManager, executor),
    EquipmentCache {

    // ==================== AbstractTieredCacheService Implementation ====================

    override fun isValidNullMarker(value: EquipmentResponse?): Boolean = value != null && NULL_MARKER_CLASS == value.characterClass

    // ==================== EquipmentCache Interface Implementation ====================

    /** 캐시 조회 로직 (L1 → L2 → Warm-up) */
    override fun getValidCache(ocid: String): Optional<EquipmentResponse>? = getFromTieredCache(ocid, EquipmentResponse::class.java)

    /** Negative 캐시 존재 여부 확인 */
    override fun hasNegativeCache(ocid: String): Boolean = executor.executeOrDefault(
        {
            val cached = tieredCache.get(ocid, EquipmentResponse::class.java)
            cached != null && isValidNullMarker(cached)
        },
        false,
        TaskContext.of("EquipmentCache", "CheckNegative", ocid),
    )

    /** 캐시 저장 및 비동기 DB persist */
    override fun saveCache(ocid: String, response: EquipmentResponse?) {
        val context = TaskContext.of("EquipmentCache", "Save", ocid)

        executor.executeOrCatch(
            {
                // 1. Tiered 캐시 저장 (L2 → L1)
                saveToTieredCache(ocid, response, NULL_MARKER)
                // 2. 비동기 DB 저장 트리거
                triggerAsyncPersist(ocid, response)
                null
            },
            { e -> handleSaveFailure(ocid, e) },
            context,
        )
    }

    // ==================== Extended Public API ====================

    /** L1-only 캐시 조회 (Expectation 경로 전용 - L2 우회) */
    fun getValidCacheL1Only(ocid: String): Optional<EquipmentResponse> = getFromL1Only(ocid, EquipmentResponse::class.java)

    /** L1-only 캐시 저장 (Expectation 경로 전용 - L2 우회, DB 저장도 스킵) */
    fun saveCacheL1Only(ocid: String, response: EquipmentResponse?) {
        saveToL1Only(ocid, response, NULL_MARKER)
    }

    // ==================== Equipment-Specific Logic (DB Persistence) ====================

    /** 비동기 DB 저장 트리거 및 내부 예외 관측 */
    private fun triggerAsyncPersist(ocid: String, response: EquipmentResponse?) {
        if (response == null) return

        dbWorker.persist(ocid, response).exceptionally { ex -> observeAsyncError(ocid, ex) }
    }

    /** 비동기 에러 관측 (executor 내부 중첩 방지용) */
    private fun observeAsyncError(ocid: String, ex: Throwable): Void? {
        executor.executeVoidJava(
            { throw CachePersistenceException(ocid, ex) },
            TaskContext.of("EquipmentDbWorker", "AsyncPersistFailed", ocid),
        )
        return null
    }

    /** 셧다운 및 공통 예외 핸들러 */
    private fun handleSaveFailure(ocid: String, e: Throwable): EquipmentResponse? {
        if (e is IllegalStateException) {
            log.warn("[Equipment Cache] Shutdown 진행 중 - DB 저장 스킵(캐시만 유지): {}", ocid)
            return null
        }
        if (e is BaseException) {
            throw e
        }
        if (e is RuntimeException) {
            throw e
        }
        throw CachePersistenceException(ocid, e)
    }

    companion object {
        private val log = LoggerFactory.getLogger(EquipmentCacheService::class.java)
        private const val CACHE_NAME = "equipment"
        private const val NULL_MARKER_CLASS = "NEGATIVE_MARKER"

        // Kotlin data class is immutable - use constructor with characterClass marker
        private val NULL_MARKER = EquipmentResponse(
            date = null,
            characterGender = null,
            characterClass = NULL_MARKER_CLASS,
            presetNo = null,
            itemEquipment = null,
            itemEquipmentPreset1 = null,
            itemEquipmentPreset2 = null,
            itemEquipmentPreset3 = null,
            dragonEquipment = null,
            mechanicEquipment = null,
            title = null,
        )
    }
}
