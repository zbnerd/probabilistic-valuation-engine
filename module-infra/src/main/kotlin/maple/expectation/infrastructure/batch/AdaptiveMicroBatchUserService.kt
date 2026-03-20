package maple.expectation.infrastructure.batch

import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.annotation.PostConstruct
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import maple.expectation.infrastructure.config.AdaptiveMicroBatchProperties
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.springframework.cache.Cache

private val log = KotlinLogging.logger {}

/**
 * 적응형 마이크로 배칭(Adaptive Micro-Batching) 사용자 조회 서비스
 *
 * <h3>핵심 기능</h3>
 *
 * <ul>
 *   <li>Step 1: 로컬 캐시(Caffeine) 확인 + Request Coalescing</li>
 *   <li>Step 2: Semaphore 기반 Adaptive Routing (Fast Lane vs Batch Lane)</li>
 *   <li>Step 3: 백그라운드 마이크로 배치 워커 (IN 쿼리 일괄 처리)</li>
 * </ul>
 *
 * <h3>동작 플로우</h3>
 *
 * <pre>
 * ┌─────────────┐     ┌──────────────────┐     ┌─────────────────┐
 * │   Client    │────>│  Caffeine Cache  │────>│  Cache Hit?     │
 * └─────────────┘     └──────────────────┘     └────────┬────────┘
 *                                                        │ No
 *                                                        ▼
 *                                             ┌─────────────────────┐
 *                                             │ Request Coalescing  │
 *                                             │ (inFlightRequests)  │
 *                                             └──────────┬──────────┘
 *                                                        │
 *                                                        ▼
 *                                             ┌─────────────────────┐
 *                                             │ semaphore.tryAcquire│
 *                                             └──────────┬──────────┘
 *                                                        │
 *                              ┌─────────────────────────┼─────────────────────────┐
 *                              │ Success                 │                         │ Failure
 *                              ▼                                                   ▼
 *                    ┌─────────────────┐                               ┌─────────────────────┐
 *                    │   Fast Lane     │                               │    Batch Lane       │
 *                    │ (단건 쿼리)      │                               │  (Channel 적재)     │
 *                    └─────────────────┘                               └─────────────────────┘
 * </pre>
 *
 * @param T 조회 결과 타입
 * @see AdaptiveMicroBatchProperties
 * @see BatchRequest
 */
// Note: @Service removed - this is a generic class that must be instantiated manually with specific loaders
// Used only in tests and specialized configurations
// AdaptiveMicroBatchProperties is enabled via AdaptiveMicroBatchConfig
class AdaptiveMicroBatchUserService<T : Any>(
    private val properties: AdaptiveMicroBatchProperties,
    private val logicExecutor: LogicExecutor,
    private val meterRegistry: MeterRegistry,
    private val cache: Cache,
    private val singleLoader: (String) -> T?,
    private val batchLoader: (List<String>) -> Map<String, T>,
) {
    /** Semaphore: Fast Lane 동시 실행 제한 */
    private val semaphore = Semaphore(properties.semaphorePermits)

    /** Batch Channel: Batch Lane 요청 큐 */
    private val batchChannel = Channel<BatchRequest<T>>(Channel.UNLIMITED)

    /** Request Coalescing: 진행 중인 요청 맵 (CompletableFuture 기반) */
    private val inFlightRequests = ConcurrentHashMap<String, CompletableFuture<T?>>()

    /** Coroutine Scope: 백그라운드 워커 실행용 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Metrics
    private val cacheHitCounter: Counter
    private val fastLaneCounter: Counter
    private val batchLaneCounter: Counter
    private val batchSizeTimer: Timer
    private val timeoutCounter: Counter
    private val errorCounter: Counter

    init {
        val cacheName = cache.name
        cacheHitCounter = Counter.builder("adaptive_batch_cache_hit")
            .tag("cache", cacheName)
            .register(meterRegistry)

        fastLaneCounter = Counter.builder("adaptive_batch_lane")
            .tag("type", "fast")
            .tag("cache", cacheName)
            .register(meterRegistry)

        batchLaneCounter = Counter.builder("adaptive_batch_lane")
            .tag("type", "batch")
            .tag("cache", cacheName)
            .register(meterRegistry)

        batchSizeTimer = Timer.builder("adaptive_batch_size")
            .tag("cache", cacheName)
            .register(meterRegistry)

        timeoutCounter = Counter.builder("adaptive_batch_timeout")
            .tag("cache", cacheName)
            .register(meterRegistry)

        errorCounter = Counter.builder("adaptive_batch_error")
            .tag("cache", cacheName)
            .register(meterRegistry)
    }

    /**
     * 애플리케이션 기동 시 백그라운드 배치 워커 시작
     */
    @PostConstruct
    fun startBatchWorker() {
        scope.launch {
            log.info { "[AdaptiveMicroBatch] Batch worker started: permits=${properties.semaphorePermits}, batchSize=${properties.batchMaxSize}, waitMs=${properties.batchMaxWaitMs}" }
            batchWorkerLoop()
        }
    }

    /**
     * 키로 엔티티 조회 (동기 버전)
     *
     * <h3>Step 1: 로컬 캐시 확인</h3>
     * <p>Caffeine 캐시에 키가 있으면 즉시 반환
     *
     * <h3>Step 2: Request Coalescing</h3>
     * <p>동일 키에 대한 동시 요청을 CompletableFuture로 병합
     *
     * <h3>Step 3: Adaptive Routing</h3>
     * <p>Semaphore 획득 성공 → Fast Lane (단건 쿼리)
     * <p>Semaphore 획득 실패 → Batch Lane (Channel 적재)
     *
     * @param key 조회 키
     * @return 조회 결과 (없으면 null)
     * @throws TimeoutException 요청 타임아웃 초과 시
     */
    fun getByKey(key: String): T? {
        // Step 1: 로컬 캐시 확인
        val cached = getCachedValue(key)
        if (cached != null) {
            cacheHitCounter.increment()
            return cached
        }

        // Step 2: Request Coalescing
        val newFuture = CompletableFuture<T?>()
        val existingFuture = inFlightRequests.putIfAbsent(key, newFuture)

        // 이미 진행 중인 요청이 있으면 대기
        if (existingFuture != null) {
            return awaitWithTimeout(existingFuture, key)
        }

        // Step 3: Adaptive Routing
        return routeRequest(key, newFuture)
    }

    /**
     * 키로 엔티티 조회 (suspend 버전)
     */
    suspend fun getByKeySuspend(key: String): T? {
        // Step 1: 로컬 캐시 확인
        val cached = getCachedValue(key)
        if (cached != null) {
            cacheHitCounter.increment()
            return cached
        }

        // Step 2: Request Coalescing
        val newFuture = CompletableFuture<T?>()
        val existingFuture = inFlightRequests.putIfAbsent(key, newFuture)

        // 이미 진행 중인 요청이 있으면 대기
        if (existingFuture != null) {
            return awaitWithTimeoutSuspend(existingFuture, key)
        }

        // Step 3: Adaptive Routing
        return routeRequestSuspend(key, newFuture)
    }

    /**
     * 캐시에서 값 조회
     */
    @Suppress("UNCHECKED_CAST")
    private fun getCachedValue(key: String): T? {
        val wrapper = cache.get(key)
        return wrapper?.get() as? T
    }

    /**
     * 적응형 라우팅 (Fast Lane vs Batch Lane) - 동기 버전
     */
    private fun routeRequest(key: String, future: CompletableFuture<T?>): T? = if (semaphore.tryAcquire()) {
        try {
            executeFastLane(key, future)
        } finally {
            semaphore.release()
            cleanupInFlight(key)
        }
    } else {
        executeBatchLane(key, future)
    }

    /**
     * 적응형 라우팅 (Fast Lane vs Batch Lane) - suspend 버전
     */
    private suspend fun routeRequestSuspend(key: String, future: CompletableFuture<T?>): T? = if (semaphore.tryAcquire()) {
        try {
            executeFastLane(key, future)
        } finally {
            semaphore.release()
            cleanupInFlight(key)
        }
    } else {
        executeBatchLaneSuspend(key, future)
    }

    /**
     * Fast Lane: 즉시 단건 쿼리 실행
     */
    private fun executeFastLane(key: String, future: CompletableFuture<T?>): T? {
        fastLaneCounter.increment()
        log.debug { "[AdaptiveMicroBatch] Fast Lane: key=$key" }

        val context = TaskContext.of("AdaptiveBatch", "FastLane", key)
        val result = logicExecutor.executeOrDefault(
            { singleLoader(key) },
            null,
            context,
        )

        if (result != null) {
            cache.put(key, result)
        }
        future.complete(result)
        return result
    }

    /**
     * Batch Lane: Channel에 요청 적재 후 대기 - 동기 버전
     */
    private fun executeBatchLane(key: String, future: CompletableFuture<T?>): T? {
        batchLaneCounter.increment()
        log.debug { "[AdaptiveMicroBatch] Batch Lane: key=$key" }

        // Channel에 비동기로 전송
        scope.launch {
            batchChannel.send(BatchRequest(key, future))
        }

        return awaitWithTimeout(future, key)
    }

    /**
     * Batch Lane: Channel에 요청 적재 후 대기 - suspend 버전
     */
    private suspend fun executeBatchLaneSuspend(key: String, future: CompletableFuture<T?>): T? {
        batchLaneCounter.increment()
        log.debug { "[AdaptiveMicroBatch] Batch Lane (suspend): key=$key" }

        batchChannel.send(BatchRequest(key, future))
        return awaitWithTimeoutSuspend(future, key)
    }

    /**
     * 타임아웃과 함께 결과 대기 - 동기 버전
     */
    private fun awaitWithTimeout(future: CompletableFuture<T?>, key: String): T? = try {
        future.get(properties.requestTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    } catch (e: java.util.concurrent.TimeoutException) {
        timeoutCounter.increment()
        log.warn { "[AdaptiveMicroBatch] Request timeout: key=$key, timeoutMs=${properties.requestTimeoutMs}" }
        cleanupInFlight(key)
        throw TimeoutException("Request timeout after ${properties.requestTimeoutMs}ms for key: $key")
    } catch (e: Exception) {
        errorCounter.increment()
        log.error(e) { "[AdaptiveMicroBatch] Request failed: key=$key" }
        cleanupInFlight(key)
        throw e
    }

    /**
     * 타임아웃과 함께 결과 대기 - suspend 버전
     */
    private suspend fun awaitWithTimeoutSuspend(future: CompletableFuture<T?>, key: String): T? = try {
        withTimeout(properties.requestTimeoutMs) {
            future.await()
        }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        timeoutCounter.increment()
        log.warn { "[AdaptiveMicroBatch] Request timeout (suspend): key=$key, timeoutMs=${properties.requestTimeoutMs}" }
        cleanupInFlight(key)
        throw TimeoutException("Request timeout after ${properties.requestTimeoutMs}ms for key: $key")
    }

    /**
     * 백그라운드 배치 워커 루프
     *
     * <h3>배치 실행 조건</h3>
     * <ul>
     *   <li>최대 대기 시간: batchMaxWaitMs (기본 10ms)</li>
     *   <li>최대 크기: batchMaxSize (기본 50개)</li>
     *   <li>둘 중 먼저 도달하는 조건으로 배치 실행</li>
     * </ul>
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun batchWorkerLoop() {
        val batch = mutableListOf<BatchRequest<T>>()

        while (true) {
            batch.clear()

            // 첫 요청 대기 (blocking)
            val first = batchChannel.receive()
            batch.add(first)

            // 배치 수집 (non-blocking, timeout)
            val deadline = System.currentTimeMillis() + properties.batchMaxWaitMs
            while (batch.size < properties.batchMaxSize) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break

                @Suppress("DEPRECATION_ERROR")
                val request = batchChannel.tryReceive().getOrNull()
                if (request != null) {
                    batch.add(request)
                } else {
                    // 짧은 대기 후 재시도
                    delay(1)
                    if (System.currentTimeMillis() >= deadline) break
                }
            }

            // 배치 실행
            if (batch.isNotEmpty()) {
                executeBatch(batch)
            }
        }
    }

    /**
     * 배치 실행 (IN 쿼리 일괄 처리)
     */
    private suspend fun executeBatch(batch: List<BatchRequest<T>>) {
        val start = System.nanoTime()
        val uniqueKeys = batch.map { it.key }.distinct()

        log.debug { "[AdaptiveMicroBatch] Batch executing: size=${batch.size}, uniqueKeys=${uniqueKeys.size}" }

        uniqueKeys.chunked(properties.chunkSize).forEach { chunk ->
            processChunk(chunk, batch)
        }

        val durationMs = (System.nanoTime() - start) / 1_000_000
        batchSizeTimer.record(Duration.ofMillis(durationMs))

        log.info { "[AdaptiveMicroBatch] Batch completed: size=${batch.size}, uniqueKeys=${uniqueKeys.size}, durationMs=$durationMs" }
    }

    /**
     * 청크 단위 처리
     */
    private fun processChunk(keys: List<String>, batch: List<BatchRequest<T>>) {
        val context = TaskContext.of("AdaptiveBatch", "ProcessChunk", "${keys.size}")

        logicExecutor.executeVoid({
            val results = batchLoader(keys)
            val keyToFuture = batch.associateBy { it.key }

            keys.forEach { key ->
                val result = results[key]
                val future = keyToFuture[key]?.future

                if (result != null) {
                    cache.put(key, result)
                }
                future?.complete(result)
                cleanupInFlight(key)
            }
        }, context)
    }

    /**
     * inFlightRequests 정리
     */
    private fun cleanupInFlight(key: String) {
        inFlightRequests.remove(key)
    }

    /**
     * 현재 진행 중인 요청 수 (모니터링용)
     */
    fun getInFlightCount(): Int = inFlightRequests.size
}
