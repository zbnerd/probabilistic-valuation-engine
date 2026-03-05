package maple.expectation.infrastructure.concurrency

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import maple.expectation.core.port.out.redis.RedisOperationPort
import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.SystemException
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.Function
import java.util.function.Supplier

/**
 * Distributed Single-flight Executor (Issue #283 P0-4 Fix)
 *
 * <h4>핵심 기능</h4>
 *
 * <ul>
 *   <li>동일 키에 대한 동시 요청 N개 중 실제 계산은 1회만 수행 (Leader)</li>
 *   <li>나머지 요청은 Leader의 결과를 공유 (Follower)</li>
 *   <li>Redis 기반 분산 상태로 인스턴스 간 Single-Flight 보장</li>
 *   <li>Follower 타임아웃 시 fallback 함수 실행</li>
 * </ul>
 *
 * <h4>Redis 키 구조</h4>
 *
 * <ul>
 *   <li>In-flight 키: {single-flight}:{keyHash} (TTL: leaderLockSeconds)</li>
 *   <li>결과 캐시 키: {single-flight}:result:{keyHash} (TTL: resultTtl)</li>
 * </ul>
 *
 * <h4>분산 상태 관리</h4>
 *
 * <ol>
 *   <li>Leader: Redis SET NX로 in-flight 키 확보 (선점)</li>
 *   <li>계산 완료: 결과를 Redis Cache에 저장, in-flight 키 삭제</li>
 *   <li>Follower: Redis에서 in-flight 키 확인 → 있으면 결과 대기 (polling)</li>
 * </ol>
 *
 * @param T 계산 결과 타입
 * @see SingleFlightExecutor 인-메모리 구현 (테스트용)
 */
class DistributedSingleFlightExecutor<T>(
    /** Follower 대기 타임아웃 (초) */
    private val followerTimeoutSeconds: Int,

    /** 비동기 작업 실행용 Executor */
    private val executor: Executor,

    /** Follower 타임아웃 시 fallback 함수 (key → result) */
    private val timeoutFallback: Function<String, T>?,

    /** Redis Operation Port (분산 상태) - ADR-012 DIP 준수 */
    private val redisOperationPort: RedisOperationPort,

    /** LogicExecutor */
    private val logicExecutor: LogicExecutor,

    /** Leader 잠금 유지 시간 (초) - 계산 시간 고려하여 충분히 설정 */
    private val leaderLockSeconds: Int = 30,

    /** 결과 캐시 TTL (초) - Follower가 결과를 조회할 수 있는 시간 */
    private val resultTtlSeconds: Int = 60
) {
    companion object {
        private val log = LoggerFactory.getLogger(DistributedSingleFlightExecutor::class.java)
        /** Redis 키 접두사 (Hash Tag for Cluster) */
        private const val KEY_PREFIX = "{single-flight}:"

        private const val RESULT_PREFIX = "{single-flight}:result:"
    }

    /** 로컬 결과 캐시 (Redis 조회 최적화) */
    private val localResultCache: Cache<String, CompletableFuture<T>> = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofSeconds(resultTtlSeconds.toLong()))
        .maximumSize(1000)
        .build()

    /**
     * Single-flight 비동기 실행
     *
     * <h4>흐름</h4>
     *
     * <ol>
     *   <li>키에 대한 in-flight 엔트리 Redis에서 확인 (SET NX)</li>
     *   <li>없으면 Leader로 등록 후 계산 시작</li>
     *   <li>있으면 Follower로 Leader 결과 대기 (Redis 결과 캐시 폴링)</li>
     * </ol>
     *
     * @param key 계산 식별 키 (캐시 키 등)
     * @param asyncSupplier 비동기 계산 로직
     * @return 계산 결과 Future
     */
    fun executeAsync(key: String, asyncSupplier: Supplier<CompletableFuture<T>>): CompletableFuture<T> {
        val hashKey = hashKey(key)
        val inFlightKey = KEY_PREFIX + hashKey
        val resultKey = RESULT_PREFIX + hashKey

        // 로컬 캐시 확인 (Redis 조회 최적화)
        val cached = localResultCache.getIfPresent(resultKey)
        if (cached != null && cached.isDone) {
            log.debug("[DistributedSingleFlight] Local cache hit for key: {}", maskKey(key))
            return copyFuture(cached)
        }

        // Leader 선점 시도
        val acquired = tryAcquireLeadership(inFlightKey)

        return if (acquired) {
            // Leader: 계산 수행
            executeAsLeader(key, hashKey, inFlightKey, resultKey, asyncSupplier)
        } else {
            // Follower: 결과 대기
            executeAsFollower(key, hashKey, resultKey)
        }
    }

    /**
     * Leader 선점 시도 (Redis SET NX)
     *
     * @return true: 선점 성공 (Leader), false: 선점 실패 (Follower)
     */
    private fun tryAcquireLeadership(inFlightKey: String): Boolean {
        return logicExecutor.executeOrDefault(
            {
                // SET NX (존재하지 않을 때만 설정)
                val acquired = redisOperationPort.trySet(inFlightKey, true, Duration.ofSeconds(leaderLockSeconds.toLong()))
                if (acquired) {
                    log.debug("[DistributedSingleFlight] Leadership acquired: {}", maskKey(inFlightKey))
                }
                acquired
            },
            false,
            TaskContext.of("DistributedSingleFlight", "TryAcquireLeadership", inFlightKey)
        )
    }

    /** Leader 비동기 실행 (계산 + Redis 결과 저장 + cleanup) */
    private fun executeAsLeader(
        key: String,
        hashKey: String,
        inFlightKey: String,
        resultKey: String,
        asyncSupplier: Supplier<CompletableFuture<T>>
    ): CompletableFuture<T> {
        val promise = CompletableFuture<T>()

        // 로컬 캐시에 등록 (Follower가 로컬에서 확인 가능)
        localResultCache.put(resultKey, promise)

        // LogicExecutor 사용 (CLAUDE.md Section 12 준수)
        logicExecutor.executeVoid({
            CompletableFuture.supplyAsync({ asyncSupplier.get() }, executor)
                .thenCompose { future -> future } // flatten
                .whenComplete { result, error ->
                    // 결과를 Redis에 저장
                    saveResultToRedis(resultKey, result, error)

                    // in-flight 키 제거
                    cleanupLeaderEntry(inFlightKey)

                    // Promise 완료
                    if (error != null) {
                        val cause = unwrapCause(error)
                        log.error(
                            "[DistributedSingleFlight] Leader failed for key: {}",
                            maskKey(key),
                            cause
                        )
                        promise.completeExceptionally(cause)
                    } else {
                        promise.complete(result)
                    }
                }
        }, TaskContext.of("DistributedSingleFlight", "ExecuteAsLeader", maskKey(key)))

        return promise
    }

    /** 결과를 Redis에 저장 (Leader) */
    private fun saveResultToRedis(resultKey: String, result: T?, error: Throwable?) {
        logicExecutor.executeVoid({
            if (error == null) {
                // 성공 결과 저장
                @Suppress("UNCHECKED_CAST")
                redisOperationPort.set(resultKey, result as Any, Duration.ofSeconds(resultTtlSeconds.toLong()))
            } else {
                // 실패 결과도 저장 (Follower가 동일하게 실패하도록)
                redisOperationPort.set("$resultKey:error", error.javaClass.name, Duration.ofSeconds(resultTtlSeconds.toLong()))
            }
            log.debug("[DistributedSingleFlight] Result saved to Redis: {}", maskKey(resultKey))
        }, TaskContext.of("DistributedSingleFlight", "SaveResult", maskKey(resultKey)))
    }

    /** Leader 종료 시 정리 (in-flight 키 제거) */
    private fun cleanupLeaderEntry(inFlightKey: String) {
        logicExecutor.executeVoid({
            redisOperationPort.delete(inFlightKey)
            log.debug("[DistributedSingleFlight] In-flight key removed: {}", maskKey(inFlightKey))
        }, TaskContext.of("DistributedSingleFlight", "CleanupLeader", maskKey(inFlightKey)))
    }

    /**
     * Follower 비동기 대기 (Redis 결과 폴링)
     *
     * <p>Redis 결과 캐시를 폴링하며 Leader 결과를 기다립니다.
     */
    private fun executeAsFollower(key: String, hashKey: String, resultKey: String): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        val maskedKey = maskKey(key)

        // 비동기 폴링 시작
        logicExecutor.executeVoid(
            { pollForResult(resultKey, result, maskedKey, System.currentTimeMillis()) },
            TaskContext.of("DistributedSingleFlight", "ExecuteAsFollower", maskedKey)
        )

        return result
            .orTimeout(followerTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .exceptionally { e ->
                val cause = unwrapCause(e)

                if (cause is TimeoutException) {
                    log.warn("[DistributedSingleFlight] Follower timeout for key: {}", maskedKey)

                    if (timeoutFallback != null) {
                        return@exceptionally logicExecutor.executeOrDefault(
                            { timeoutFallback.apply(key) },
                            null,
                            TaskContext.of("DistributedSingleFlight", "Fallback", maskedKey)
                        )
                    }
                }

                // Re-throw the exception
                if (cause is RuntimeException) {
                    throw cause
                }
                throw SystemException(
                    CommonErrorCode.INTERNAL_SERVER_ERROR,
                    "SingleFlight execution failed",
                    cause
                )
            }
    }

    /** Redis 결과 폴링 (Follower) */
    private fun pollForResult(
        resultKey: String,
        result: CompletableFuture<T>,
        maskedKey: String,
        deadline: Long
    ) {
        val timeoutMs = followerTimeoutSeconds * 1000L
        val remaining = deadline + timeoutMs - System.currentTimeMillis()

        if (remaining <= 0) {
            log.warn("[DistributedSingleFlight] Poll timeout for key: {}", maskedKey)
            return
        }

        // Redis에서 결과 확인
        logicExecutor.executeVoid({
            // 먼저 에러 확인
            val errorClass: String? = redisOperationPort.get("$resultKey:error")
            if (errorClass != null) {
                result.completeExceptionally(
                    SystemException(
                        CommonErrorCode.INTERNAL_SERVER_ERROR,
                        "Leader failed: $errorClass"
                    )
                )
                return@executeVoid
            }

            // 성공 결과 확인
            val value: T? = redisOperationPort.get(resultKey)

            if (value != null) {
                // 결과 발견
                result.complete(value)
                log.debug("[DistributedSingleFlight] Result retrieved from Redis: {}", maskedKey)
                return@executeVoid
            }

            // 결과 아직 준비 안됨 -> 재시도
            if (remaining > 100) {
                // 100ms 대기 (LockSupport 사용)
                java.util.concurrent.locks.LockSupport.parkNanos(100_000_000L)
                pollForResult(resultKey, result, maskedKey, deadline)
            } else {
                log.debug("[DistributedSingleFlight] Polling exhausted for key: {}", maskedKey)
            }
        }, TaskContext.of("DistributedSingleFlight", "PollResult", maskedKey))
    }

    /**
     * 키 해시 (Redis 키 길이 제한 준수)
     *
     * @param key 원본 키
     * @return SHA-256 해시 (hex)
     */
    private fun hashKey(key: String): String {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(key.toByteArray(StandardCharsets.UTF_8))
            val hex = StringBuilder()
            for (b in hash) {
                hex.append(String.format("%02x", b))
            }
            return hex.toString()
        } catch (e: java.security.NoSuchAlgorithmException) {
            // SHA-256는 항상 존재
            throw SystemException(CommonErrorCode.INTERNAL_SERVER_ERROR, "SHA-256 not available", e)
        }
    }

    /** CompletionException unwrap */
    private fun unwrapCause(e: Throwable): Throwable {
        if (e is java.util.concurrent.CompletionException || e is java.util.concurrent.ExecutionException) {
            return e.cause ?: e
        }
        return e
    }

    /** 키 마스킹 (로깅용) */
    private fun maskKey(key: String?): String {
        if (key == null) return "null"
        if (key.length <= 8) return "***"
        return key.substring(0, 4) + "***" + key.substring(key.length - 4)
    }

    /** Future 복사 (완료된 Future의 안전한 참조 반환) */
    private fun copyFuture(future: CompletableFuture<T>): CompletableFuture<T> {
        val copy = CompletableFuture<T>()
        future.whenComplete { result, error ->
            if (error != null) {
                copy.completeExceptionally(error)
            } else {
                copy.complete(result)
            }
        }
        return copy
    }

    /** 현재 로컬 캐시 크기 (모니터링용) */
    fun getLocalCacheSize(): Long = localResultCache.estimatedSize()

    /** 로컬 캐시 비우기 */
    fun clearLocalCache() {
        localResultCache.invalidateAll()
    }
}
