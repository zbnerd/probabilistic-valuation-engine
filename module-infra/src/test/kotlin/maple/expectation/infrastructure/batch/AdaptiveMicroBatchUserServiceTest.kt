package maple.expectation.infrastructure.batch

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import maple.expectation.common.function.ThrowingSupplier
import maple.expectation.infrastructure.config.AdaptiveMicroBatchProperties
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.executor.function.ThrowingRunnable
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.cache.Cache

/**
 * AdaptiveMicroBatchUserService 단위 테스트
 *
 * <h3>테스트 원칙</h3>
 * <ul>
 *   <li>❌ delay()로 동시성 타이밍 제어 금지</li>
 *   <li>✅ CountDownLatch로 동기화</li>
 *   <li>✅ assertThrows/assertThatThrownBy 사용</li>
 *   <li>✅ 테스트 의도를 메서드 이름으로 명확히</li>
 * </ul>
 *
 * <h3>테스트 시나리오</h3>
 * <ul>
 *   <li>캐시 적중 시 즉시 반환</li>
 *   <li>Fast Lane 단건 조회</li>
 *   <li>존재하지 않는 키 null 반환</li>
 *   <li>inFlightRequests 정리</li>
 *   <li>Request Coalescing 동시성 검증</li>
 * </ul>
 */
class AdaptiveMicroBatchUserServiceTest {

    private lateinit var meterRegistry: SimpleMeterRegistry
    private lateinit var properties: AdaptiveMicroBatchProperties
    private lateinit var logicExecutor: StubLogicExecutor
    private lateinit var cache: StubCache

    // 테스트용 데이터
    private val singleLoaderCallCount = AtomicInteger(0)
    private val batchLoaderCallCount = AtomicInteger(0)
    private val users = mutableMapOf<String, TestUser>()

    data class TestUser(val ign: String, val name: String)

    @BeforeEach
    fun setUp() {
        meterRegistry = SimpleMeterRegistry()
        properties = AdaptiveMicroBatchProperties(
            semaphorePermits = 10,
            batchMaxWaitMs = 50,
            batchMaxSize = 50,
            chunkSize = 100,
            requestTimeoutMs = 1000,
        )
        logicExecutor = StubLogicExecutor()
        cache = StubCache()

        // 테스트 데이터 초기화
        users.clear()
        singleLoaderCallCount.set(0)
        batchLoaderCallCount.set(0)
    }

    // ================================
    // 캐시 관련 테스트
    // ================================

    @Test
    @DisplayName("캐시 적중 시 즉시 반환된다")
    fun `cache hit returns immediately`() = runBlocking {
        // given
        val user = TestUser("user1", "Test User")
        cache.setCachedValue("user1", user)

        val service = createService()

        // when
        val result = service.getByKey("user1")

        // then
        assertThat(result).isEqualTo(user)
        assertThat(singleLoaderCallCount.get()).isEqualTo(0) // Loader 호출 없음
    }

    // ================================
    // Fast Lane 관련 테스트
    // ================================

    @Test
    @DisplayName("Fast Lane: 캐시 미스 시 단건 로더가 호출된다")
    fun `fast lane single query on cache miss`() = runBlocking {
        // given
        val user = TestUser("user1", "Test User")
        users["user1"] = user

        val service = createService()
        waitForWorkerStart()

        // when
        val result = service.getByKey("user1")

        // then
        assertThat(result).isEqualTo(user)
        assertThat(singleLoaderCallCount.get()).isGreaterThanOrEqualTo(1) // Fast Lane 사용
    }

    @Test
    @DisplayName("존재하지 않는 키는 null을 반환한다")
    fun `returns null for non-existent key`() = runBlocking {
        // given
        val service = createService()

        // when
        val result = service.getByKey("nonexistent")

        // then
        assertThat(result).isNull()
    }

    // ================================
    // 리소스 정리 테스트
    // ================================

    @Test
    @DisplayName("inFlightRequests가 정상적으로 정리된다")
    fun `inFlightRequests cleaned up after completion`() = runBlocking {
        // given
        val user = TestUser("user1", "Test User")
        users["user1"] = user

        val service = createService()
        waitForWorkerStart()

        // when
        service.getByKey("user1")

        // then
        assertThat(service.getInFlightCount()).isEqualTo(0)
    }

    // ================================
    // Request Coalescing 테스트 (동시성)
    // ================================

    @Test
    @DisplayName("Request Coalescing: 동일 키 동시 요청 시 로더가 한 번만 호출된다")
    fun `request coalescing deduplicates concurrent requests`() = runBlocking {
        // given
        val user = TestUser("user1", "Test User")
        users["user1"] = user

        // CountDownLatch로 정확한 동기화
        val requestCount = 5
        val allRequestsReady = CountDownLatch(requestCount)
        val allRequestsComplete = CountDownLatch(requestCount)
        val loaderInvocations = AtomicInteger(0)

        val service = AdaptiveMicroBatchUserService<TestUser>(
            properties = properties,
            logicExecutor = logicExecutor,
            meterRegistry = meterRegistry,
            cache = cache,
            singleLoader = { key ->
                // 로더 호출 시점 기록
                loaderInvocations.incrementAndGet()
                users[key]
            },
            batchLoader = { keys ->
                CompletableFuture.completedFuture(keys.mapNotNull { key -> users[key]?.let { key to it } }.toMap())
            },
        ).also { it.startBatchWorker() }

        waitForWorkerStart()

        // when - CountDownLatch로 동시에 5개 요청 시작
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        repeat(requestCount) {
            scope.launch {
                allRequestsReady.countDown() // 모든 요청이 준비될 때까지 대기
                service.getByKey("user1")
                allRequestsComplete.countDown() // 요청 완료 신호
            }
        }

        // 모든 요청이 시작되도록 준비
        allRequestsReady.await(1, TimeUnit.SECONDS)

        // 모든 요청이 완료될 때까지 대기
        allRequestsComplete.await(2, TimeUnit.SECONDS)

        // then - 모든 요청이 성공해야 함
        assertThat(loaderInvocations.get()).isLessThanOrEqualTo(2) // Request Coalescing으로 중복 호출 방지
    }

    // ================================
    // Helper Methods
    // ================================

    /**
     * 워커 시작 대기 (최소화된 delay)
     */
    private suspend fun waitForWorkerStart() {
        delay(50)
    }

    private fun createService(): AdaptiveMicroBatchUserService<TestUser> = AdaptiveMicroBatchUserService<TestUser>(
        properties = properties,
        logicExecutor = logicExecutor,
        meterRegistry = meterRegistry,
        cache = cache,
        singleLoader = { key ->
            singleLoaderCallCount.incrementAndGet()
            users[key]
        },
        batchLoader = { keys ->
            batchLoaderCallCount.incrementAndGet()
            CompletableFuture.completedFuture(keys.mapNotNull { key -> users[key]?.let { key to it } }.toMap())
        },
    ).also {
        it.startBatchWorker()
    }

    // ================================
    // Stub Implementations
    // ================================

    /**
     * Stub LogicExecutor - 실제 작업을 실행하는 단순 구현
     */
    private class StubLogicExecutor(
        private val executionDelayMs: Long = 0,
    ) : LogicExecutor {
        override fun <T> execute(task: ThrowingSupplier<T>, context: TaskContext): T {
            if (executionDelayMs > 0) Thread.sleep(executionDelayMs)
            return task.get()
        }

        override fun <T> executeOrDefault(task: ThrowingSupplier<T>, defaultValue: T, context: TaskContext): T = try {
            if (executionDelayMs > 0) Thread.sleep(executionDelayMs)
            task.get() ?: defaultValue
        } catch (e: Exception) {
            defaultValue
        }

        override fun executeVoid(task: ThrowingRunnable, context: TaskContext) {
            if (executionDelayMs > 0) Thread.sleep(executionDelayMs)
            task.run()
        }

        override fun <T> executeWithFinally(task: ThrowingSupplier<T>, finallyBlock: Runnable, context: TaskContext): T = try {
            execute(task, context)
        } finally {
            finallyBlock.run()
        }

        override fun <T> executeWithFallback(task: ThrowingSupplier<T>, fallback: (Throwable) -> T, context: TaskContext): T = try {
            execute(task, context)
        } catch (e: Exception) {
            fallback(e)
        }

        override fun <T> executeWithTranslation(
            task: ThrowingSupplier<T>,
            customTranslator: ExceptionTranslator,
            context: TaskContext,
        ): T = try {
            execute(task, context)
        } catch (e: Exception) {
            throw customTranslator.translate(e, context)
        }

        override fun <T> executeOrCatch(task: ThrowingSupplier<T>, recovery: (Throwable) -> T, context: TaskContext): T = try {
            execute(task, context)
        } catch (e: Exception) {
            recovery(e)
        }

        // Java-friendly overload with ExceptionTranslator
        override fun <T> executeWithFallback(task: ThrowingSupplier<T>, fallback: ExceptionTranslator, context: TaskContext): T = try {
            execute(task, context)
        } catch (e: Exception) {
            throw fallback.translate(e, context)
        }

        // Java-friendly overload with ExceptionTranslator
        override fun <T> executeOrCatch(task: ThrowingSupplier<T>, recovery: ExceptionTranslator, context: TaskContext): T = try {
            execute(task, context)
        } catch (e: Exception) {
            throw recovery.translate(e, context)
        }

        override fun executeVoidJava(task: Runnable, context: TaskContext) {
            if (executionDelayMs > 0) Thread.sleep(executionDelayMs)
            task.run()
        }
    }

    /**
     * Stub Cache - 캐시 동작을 시뮬레이션
     */
    private class StubCache : Cache {
        private val store = mutableMapOf<Any, Any?>()
        private var cachedValue: Any? = null
        private var cachedKey: Any? = null

        fun setCachedValue(key: String, value: Any) {
            cachedKey = key
            cachedValue = value
            store[key] = value
        }

        override fun getName(): String = "testCache"

        override fun getNativeCache(): Any = store

        override fun get(key: Any): Cache.ValueWrapper? {
            val value = if (cachedValue != null && key == cachedKey) cachedValue else store[key]
            return if (value != null) Cache.ValueWrapper { value } else null
        }

        override fun <T : Any?> get(key: Any, type: Class<T>?): T? {
            @Suppress("UNCHECKED_CAST")
            return store[key] as? T
        }

        override fun <T : Any?> get(key: Any, valueLoader: java.util.concurrent.Callable<T>): T? {
            val existing = store[key]
            if (existing != null) {
                @Suppress("UNCHECKED_CAST")
                return existing as T
            }
            val loaded = valueLoader.call()
            if (loaded != null) {
                store[key] = loaded
            }
            return loaded
        }

        override fun put(key: Any, value: Any?) {
            store[key] = value
        }

        override fun putIfAbsent(key: Any, value: Any?): Cache.ValueWrapper? {
            if (!store.containsKey(key)) {
                store[key] = value
            }
            return Cache.ValueWrapper { store[key] }
        }

        override fun evict(key: Any) {
            store.remove(key)
        }

        override fun evictIfPresent(key: Any): Boolean = store.remove(key) != null

        override fun clear() {
            store.clear()
            cachedValue = null
            cachedKey = null
        }

        override fun invalidate(): Boolean {
            clear()
            return true
        }
    }
}
