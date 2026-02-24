package maple.expectation.infrastructure.concurrency

import lombok.extern.slf4j.Slf4j.Slf4j
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.Function
import java.util.function.Supplier

/**
 * Single-flight 비동기 실행기
 *
 * <h4>핵심 기능</h4>
 *
 * <ul>
 *   <li>동일 키에 대한 동시 요청 N개 중 실제 계산은 1회만 수행 (Leader)</li>
 *   <li>나머지 요청은 Leader의 결과를 공유 (Follower)</li>
 *   <li>Follower 타임아웃 시 fallback 함수 실행</li>
 *   <li>Generic 타입 지원으로 재사용 가능</li>
 * </ul>
 *
 * <h4>사용 예시</h4>
 *
 * <pre>{@code
 * val executor = SingleFlightExecutor<MyResult>(
 *     5, computeExecutor, cacheService::getFromCache
 * )
 *
 * val result = executor.executeAsync(
 *     "cache-key",
 *     { expensiveComputation() }
 * )
 * }</pre>
 *
 * @param T 계산 결과 타입
 * @see DistributedSingleFlightService 분산 환경에서의 Single-Flight (Issue #283 P0-4)
 * @see <a href="https://github.com/issue/158">Issue #158: Single-flight 패턴</a>
 */
@Slf4j
class SingleFlightExecutor<T>(
    /** Follower 대기 타임아웃 (초) */
    private val followerTimeoutSeconds: Int,

    /** 비동기 작업 실행용 Executor */
    private val executor: Executor,

    /** Follower 타임아웃 시 fallback 함수 (key → result) */
    private val timeoutFallback: Function<String, T>?
) {
    /** 진행 중인 계산 맵 */
    private val inFlight: ConcurrentHashMap<String, InFlightEntry<T>> = ConcurrentHashMap()

    /** InFlight 엔트리 */
    private data class InFlightEntry<T>(val promise: CompletableFuture<T>)

    /**
     * Single-flight 비동기 실행
     *
     * <h4>흐름</h4>
     *
     * <ol>
     *   <li>키에 대한 inFlight 엔트리 확인</li>
     *   <li>없으면 Leader로 등록 후 계산 시작</li>
     *   <li>있으면 Follower로 Leader 결과 대기</li>
     * </ol>
     *
     * @param key 계산 식별 키 (캐시 키 등)
     * @param asyncSupplier 비동기 계산 로직
     * @return 계산 결과 Future
     */
    fun executeAsync(key: String, asyncSupplier: Supplier<CompletableFuture<T>>): CompletableFuture<T> {
        val promise = CompletableFuture<T>()
        val newEntry = InFlightEntry(promise)
        val existing = inFlight.putIfAbsent(key, newEntry)

        return if (existing == null) {
            executeAsLeader(key, newEntry, asyncSupplier)
        } else {
            executeAsFollower(key, existing.promise)
        }
    }

    /**
     * Leader 비동기 실행 (계산 + promise 완료 + cleanup)
     *
     * <p><b>CRITICAL:</b> asyncSupplier.get()이 동기 예외를 던질 경우에도 반드시 cleanup이 수행되어야 함 (메모리 누수 + 데드락 방지)
     *
     * <p><b>CLAUDE.md Section 12 준수:</b> CompletableFuture.handle()로 동기 예외 처리 (try-catch 제거)
     */
    private fun executeAsLeader(
        key: String,
        entry: InFlightEntry<T>,
        asyncSupplier: Supplier<CompletableFuture<T>>
    ): CompletableFuture<T> {
        val promise = entry.promise

        // CLAUDE.md 준수: CompletableFuture 체이닝으로 동기 예외 처리 (try-catch 제거)
        return CompletableFuture.supplyAsync({ asyncSupplier.get() }, executor)
            .thenCompose { future -> future } // flatten CompletableFuture<CompletableFuture<T>>
            .whenComplete { result, error ->
                if (error != null) {
                    val cause = unwrapCause(error)
                    log.error("[SingleFlight] Leader failed for key: {}", maskKey(key), cause)
                    promise.completeExceptionally(cause)
                } else {
                    promise.complete(result)
                }
            }
            .whenComplete { _, _ -> cleanupLeaderEntry(promise, key, entry) }
    }

    /** Leader 종료 시 정리 (promise 가드 + inFlight 제거) */
    private fun cleanupLeaderEntry(promise: CompletableFuture<T>, key: String, entry: InFlightEntry<T>) {
        if (!promise.isDone) {
            promise.completeExceptionally(IllegalStateException("Leader aborted before completion"))
        }
        inFlight.remove(key, entry)
    }

    /**
     * Follower 비동기 대기 (타임아웃 + fallback)
     *
     * <p><b>P1 Fix (PR #160 Codex 지적):</b> orTimeout()이 공유 promise를 직접 수정하면 다른 follower에게 전파됨. 각
     * follower에게 독립적인 Future를 생성하여 timeout 격리.
     *
     * <p>변경 전: leaderFuture.orTimeout() → 공유 promise 오염
     *
     * <p>변경 후: 독립 Future 생성 후 orTimeout() → follower 간 격리
     */
    private fun executeAsFollower(key: String, leaderFuture: CompletableFuture<T>): CompletableFuture<T> {
        // P1 Fix: 각 follower에게 독립적인 Future 생성 (공유 promise 보호)
        val isolatedFuture = CompletableFuture<T>()
        leaderFuture.whenComplete { result, error ->
            if (error != null) {
                isolatedFuture.completeExceptionally(error)
            } else {
                isolatedFuture.complete(result)
            }
        }

        return isolatedFuture
            .orTimeout(followerTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .exceptionallyCompose { e -> handleFollowerException(key, e) }
    }

    /** Follower 예외 처리 (타임아웃 시 fallback 실행) */
    private fun handleFollowerException(key: String, e: Throwable): CompletableFuture<T> {
        val cause = unwrapCause(e)

        if (cause is TimeoutException) {
            log.warn("[SingleFlight] Follower timeout for key: {}", maskKey(key))

            if (timeoutFallback != null) {
                return CompletableFuture.supplyAsync({ timeoutFallback.apply(key) }, executor)
            }
        }

        return CompletableFuture.failedFuture(cause)
    }

    /** CompletionException unwrap */
    private fun unwrapCause(e: Throwable): Throwable {
        if (e is java.util.concurrent.CompletionException || e is java.util.concurrent.ExecutionException) {
            return e.cause ?: e
        }
        return e
    }

    /** 키 마스킹 (로깅용) */
    private fun maskKey(key: String): String {
        if (key.length <= 8) return "***"
        return key.substring(0, 4) + "***" + key.substring(key.length - 4)
    }

    /** 현재 inFlight 엔트리 수 (모니터링용) */
    fun getInFlightCount(): Int = inFlight.size
}
