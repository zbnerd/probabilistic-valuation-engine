package maple.expectation.infrastructure.cache.per

import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.redisson.api.RedissonClient
import org.redisson.api.RBucket
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.DefaultParameterNameDiscoverer
import org.springframework.core.ParameterNameDiscoverer
import org.springframework.expression.EvaluationContext
import org.springframework.expression.ExpressionParser
import org.springframework.expression.spel.standard.SpelExpressionParser
import org.springframework.expression.spel.support.StandardEvaluationContext
import org.springframework.stereotype.Component
import java.lang.reflect.Method
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import org.springframework.cache.annotation.Cacheable

/**
 * PER (Probabilistic Early Recomputation) AOP Aspect (#219)
 *
 * <h3>Cache Stampede 방지</h3>
 *
 * <p>X-Fetch 알고리즘을 사용하여 Lock 없이 확률적 백그라운드 갱신을 수행.
 *
 * <h4>처리 흐름</h4>
 *
 * <ol>
 *   <li>Cache Miss → 동기 실행 후 캐시 저장</li>
 *   <li>Cache Hit + PER 당첨 → 비동기 갱신 + Stale 데이터 반환</li>
 *   <li>Cache Hit + PER 미당첨 → 캐시 데이터 반환</li>
 * </ol>
 *
 * <h4>Non-Blocking 보장</h4>
 *
 * <ul>
 *   <li>캐시 Hit 시 항상 즉시 반환 (Stale 허용)</li>
 *   <li>백그라운드 갱신은 전용 Thread Pool에서 Fire & Forget</li>
 *   <li>갱신 실패해도 기존 데이터 유지</li>
 * </ul>
 *
 * <h3>#271 V5 Stateless Architecture 평가</h3>
 *
 * <p>{@code wrapperTypeCache}는 ConcurrentHashMap이지만 다음 이유로 인스턴스별 유지 가능:
 *
 * <ul>
 *   <li>읽기 전용 캐시: JavaType 파싱 결과 캐싱 (변경 없음)</li>
 *   <li>인스턴스별 독립: 동일 Method에 대해 동일한 JavaType 생성</li>
 *   <li>P2-GREEN-01: 성능 최적화용, 비즈니스 영향 없음</li>
 * </ul>
 *
 * <h4>5-Agent Council 합의 (P1-4)</h4>
 *
 * <ul>
 *   <li>Blue (Architect): 읽기 전용 캐시로 Scale-out 안전</li>
 *   <li>Green (Performance): 인스턴스별 캐싱으로 JVM 내 최적화</li>
 * </ul>
 */
@Aspect
@Component
class ProbabilisticCacheAspect(
    private val redissonClient: RedissonClient,
    @Qualifier("perCacheExecutor") private val perCacheExecutor: Executor,
    private val objectMapper: ObjectMapper,
    private val executor: LogicExecutor
) {
    companion object {
        private val log = LoggerFactory.getLogger(ProbabilisticCacheAspect::class.java)
    }

    private val parser: ExpressionParser = SpelExpressionParser()
    private val paramDiscoverer: ParameterNameDiscoverer = DefaultParameterNameDiscoverer()

    // P2-GREEN-01: JavaType 캐싱으로 성능 최적화
    private val wrapperTypeCache: ConcurrentHashMap<Method, JavaType> = ConcurrentHashMap()

    @Around("@annotation(probabilisticCache)")
    fun handleCache(joinPoint: ProceedingJoinPoint, probabilisticCache: ProbabilisticCache): Any? {
        val cacheKey = generateKey(joinPoint, probabilisticCache)
        val signature = joinPoint.signature as MethodSignature
        val method = signature.method

        // 1. 캐시 조회
        val bucket: RBucket<String> = redissonClient.getBucket(cacheKey)
        val cachedJson = bucket.get()

        // 2. Cache Miss → 동기 실행
        if (cachedJson == null) {
            log.debug("🔴 [PER] Cache Miss: {}", cacheKey)
            return recomputeAndCache(joinPoint, cacheKey, probabilisticCache)
        }

        // 3. PR #238 Fix: JavaType을 사용한 역직렬화 (제네릭 타입 보존)
        val cached = deserializeWrapperSafely(cachedJson, cacheKey, method)
        if (cached == null) {
            log.warn("⚠️ [PER] 역직렬화 실패, 재계산: {}", cacheKey)
            return recomputeAndCache(joinPoint, cacheKey, probabilisticCache)
        }

        // 4. Cache Hit → PER 알고리즘 체크
        if (cached.shouldRefresh(probabilisticCache.beta)) {
            log.info(
                "🎲 [PER] 조기 갱신 당첨! 백그라운드 갱신 시작 (Key: {}, TTL 남음: {}ms)",
                cacheKey,
                cached.remainingTtl()
            )

            // 비동기 갱신 (Fire & Forget) - LogicExecutor 패턴 적용
            perCacheExecutor.execute { refreshInBackground(joinPoint, cacheKey, probabilisticCache) }
        }

        // 5. Stale 데이터 즉시 반환 (Non-Blocking)
        log.debug("🟢 [PER] Cache Hit: {} (stale: {})", cacheKey, cached.isExpired())
        return cached.value
    }

    /** 원본 메서드 실행 후 캐시 저장 */
    @Throws(Throwable::class)
    private fun recomputeAndCache(
        joinPoint: ProceedingJoinPoint,
        cacheKey: String,
        annotation: ProbabilisticCache
    ): Any? {
        val start = System.currentTimeMillis()
        val result = joinPoint.proceed()
        val delta = System.currentTimeMillis() - start

        // CachedWrapper 생성 (값 + delta + expiry)
        val wrapper = CachedWrapper.of(result, delta, annotation.ttlSeconds)

        // Redis 저장 (TTL 포함) - LogicExecutor 패턴
        val bucket = redissonClient.getBucket<String>(cacheKey)
        val json = serializeWrapperSafely(wrapper, cacheKey)
        if (json != null) {
            bucket.set(json, Duration.ofSeconds(annotation.ttlSeconds))
            log.debug(
                "💾 [PER] 캐시 저장: key={}, delta={}ms, ttl={}s",
                cacheKey,
                delta,
                annotation.ttlSeconds
            )
        }

        return result
    }

    /**
     * 백그라운드 갱신 (LogicExecutor 패턴)
     *
     * <p>비동기 작업에서 발생하는 예외를 LogicExecutor로 처리하여 CLAUDE.md Section 12 (Zero try-catch) 위반 방지
     */
    private fun refreshInBackground(joinPoint: ProceedingJoinPoint, cacheKey: String, annotation: ProbabilisticCache) {
        val context = TaskContext.of("PER", "AsyncRefresh", cacheKey)

        executor.executeVoid({
            try {
                recomputeAndCache(joinPoint, cacheKey, annotation)
                log.debug("✅ [PER] 백그라운드 갱신 완료: {}", cacheKey)
            } catch (t: Throwable) {
                log.error("[PER] 백그라운드 갱신 실패: {}", cacheKey, t)
            }
        }, context)
    }

    /**
     * CachedWrapper → JSON 직렬화 (LogicExecutor 패턴)
     *
     * @param wrapper 캐시 래퍼
     * @param cacheKey 캐시 키 (로깅용)
     * @return JSON 문자열, 실패 시 null
     */
    private fun serializeWrapperSafely(wrapper: CachedWrapper<Any>, cacheKey: String): String? {
        val context = TaskContext.of("PER", "Serialize", cacheKey)

        return executor.executeOrDefault(
            { objectMapper.writeValueAsString(wrapper) },
            null,
            context
        )
    }

    /**
     * PR #238 Fix: JavaType을 사용한 JSON → CachedWrapper 역직렬화
     *
     * <h4>변경 전 (버그)</h4>
     *
     * <p>{@code CachedWrapper.class}로 역직렬화 시 제네릭 타입 정보 손실 → ClassCastException
     *
     * <h4>변경 후</h4>
     *
     * <p>메서드 반환 타입에서 JavaType을 추출하여 정확한 타입으로 역직렬화
     *
     * @param json JSON 문자열
     * @param cacheKey 캐시 키 (로깅용)
     * @param method 원본 메서드 (반환 타입 추출용)
     * @return CachedWrapper, 실패 시 null
     */
    private fun deserializeWrapperSafely(json: String, cacheKey: String, method: Method): CachedWrapper<Any>? {
        val context = TaskContext.of("PER", "Deserialize", cacheKey)

        return executor.executeOrDefault({
            // P2-GREEN-01: JavaType 캐싱 적용
            val wrapperType = wrapperTypeCache.computeIfAbsent(method) { buildWrapperType(it) }
            @Suppress("UNCHECKED_CAST")
            objectMapper.readValue(json, wrapperType) as CachedWrapper<Any>
        }, null, context)
    }

    /** P2-GREEN-01: 메서드별 CachedWrapper JavaType 생성 (캐싱용) */
    private fun buildWrapperType(method: Method): JavaType {
        val returnType = method.genericReturnType
        val valueType = objectMapper.typeFactory.constructType(returnType)
        return objectMapper.typeFactory.constructParametricType(CachedWrapper::class.java, valueType)
    }

    /** SpEL 기반 캐시 키 생성 */
    private fun generateKey(joinPoint: ProceedingJoinPoint, annotation: ProbabilisticCache): String {
        val keyExpression = annotation.key
        val cacheName = annotation.cacheName

        // key가 비어있으면 메서드 시그니처 사용
        if (keyExpression.isEmpty()) {
            return "$cacheName:${joinPoint.signature.toShortString()}"
        }

        // SpEL 파싱
        val signature = joinPoint.signature as MethodSignature
        val method = signature.method
        val args = joinPoint.args

        val evalContext = StandardEvaluationContext()
        val paramNames = paramDiscoverer.getParameterNames(method)

        if (paramNames != null) {
            paramNames.forEachIndexed { index, paramName ->
                evalContext.setVariable(paramName, args[index])
            }
        }

        val keyValue = parser.parseExpression(keyExpression).getValue(evalContext)
        return "$cacheName:$keyValue"
    }
}
