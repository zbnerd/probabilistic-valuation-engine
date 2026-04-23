package maple.expectation.core.port.inbound

import java.util.concurrent.CompletableFuture

/**
 * V4 기대값 계산 Port (ADR-005)
 *
 * <p>책임: 장비 기대값 계산 및 GZIP 응답
 *
 * <p>구현체:
 * <ul>
 *   <li>module-app/adapter/in/ExpectationV4PortAdapter - EquipmentExpectationServiceV4에 위임
 * </ul>
 */
interface ExpectationV4Port {

    /**
     * 기대값 비동기 계산
     *
     * @param userIgn 캐릭터 IGN
     * @param force 강제 재계산 여부
     * @param presetNo 프리셋 번호 (default: 1)
     * @return 기대값 응답 (Any = EquipmentExpectationResponseV4)
     */
    fun calculateExpectationAsync(userIgn: String, force: Boolean, presetNo: Int = 1): CompletableFuture<Any>

    /**
     * V5 queue/worker aware async calculation.
     */
    fun calculateExpectationAsync(userIgn: String, force: Boolean, taskId: String?, presetNo: Int = 1): CompletableFuture<Any>

    /**
     * V5 queue/worker aware async calculation.
     */
    fun calculateExpectationAsync(userIgn: String, force: Boolean, taskId: String?): CompletableFuture<Any>

    /**
     * 🔥 기대값 동기 계산 (Admission Control용)
     *
     * <p>NOTE: Admission control은 sync 작업만 관리합니다.
     * Async 작업은 외부에서 thenApplyAsync로 처리합니다.
     *
     * @param userIgn 캐릭터 IGN
     * @param force 강제 재계산 여부
     * @param presetNo 프리셋 번호 (default: 1)
     * @return 기대값 응답 (Any = EquipmentExpectationResponseV4)
     */
    fun calculateExpectation(userIgn: String, force: Boolean, presetNo: Int = 1): Any

    /**
     * V5 queue/worker aware sync calculation.
     */
    fun calculateExpectation(userIgn: String, force: Boolean, taskId: String?, presetNo: Int = 1): Any

    /**
     * V5 queue/worker aware sync calculation.
     */
    fun calculateExpectation(userIgn: String, force: Boolean, taskId: String?): Any

    /**
     * GZIP 기대값 비동기 조회
     *
     * @param userIgn 캐릭터 IGN
     * @param force 강제 재계산 여부
     * @param presetNo 프리셋 번호 (default: 1)
     * @return GZIP 바이트 배열
     */
    fun getGzipExpectationAsync(userIgn: String, force: Boolean, presetNo: Int = 1): CompletableFuture<ByteArray?>

    /**
     * 🔥 GZIP 기대값 동기 조회 (Admission Control용)
     *
     * <p>NOTE: Admission control은 sync 작업만 관리합니다.
     * Async 작업은 외부에서 thenApplyAsync로 처리합니다.
     *
     * @param userIgn 캐릭터 IGN
     * @param force 강제 재계산 여부
     * @param presetNo 프리셋 번호 (default: 1)
     * @return GZIP 바이트 배열
     */
    fun getGzipExpectation(userIgn: String, force: Boolean, presetNo: Int = 1): ByteArray?

    /**
     * Write-only calculation for Phase 1 (BS2) batch UPSERT.
     *
     * <p>Calculates expectation without DB writes (no persistence, cache, or view writes).
     * Returns Any to avoid module-core → module-web dependency.
     * Caller must cast: `as? EquipmentExpectationResponseV4`
     */
    fun calculateExpectationWriteOnly(userIgn: String, force: Boolean, taskId: String?, presetNo: Int = 1): Any

    /**
     * Write-only calculation for Phase 1 (BS2) batch UPSERT.
     *
     * <p>Calculates expectation without DB writes (no persistence, cache, or view writes).
     * Returns Any to avoid module-core → module-web dependency.
     * Caller must cast: `as? EquipmentExpectationResponseV4`
     */
    fun calculateExpectationWriteOnly(userIgn: String, force: Boolean, taskId: String?): Any

    /**
     * L1 캐시에서 GZIP 직접 조회 (Fast Path)
     *
     * @param userIgn 캐릭터 IGN
     * @return GZIP 바이트 또는 null
     */
    fun getGzipFromL1CacheDirect(userIgn: String): ByteArray?
}

/**
 * Java-friendly default methods for ExpectationV4Port.
 * These provide overloaded methods without presetNo parameter for Java callers.
 */
// Note: Since Kotlin interfaces with default parameters don't generate Java overloads,
// Java callers should use the adapter directly or pass explicit presetNo=1

/**
 * Java-friendly extension functions for ExpectationV4Port.
 * These provide overloaded methods without presetNo parameter for Java callers.
 */
// Note: Java interop is handled by the adapter layer, not by interface extensions
