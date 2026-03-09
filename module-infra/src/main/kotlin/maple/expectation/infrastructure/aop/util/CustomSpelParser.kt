package maple.expectation.infrastructure.aop.util

import java.util.concurrent.ConcurrentHashMap
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.reflect.MethodSignature
import org.slf4j.LoggerFactory
import org.springframework.expression.Expression
import org.springframework.expression.ExpressionParser
import org.springframework.expression.spel.standard.SpelExpressionParser
import org.springframework.expression.spel.support.StandardEvaluationContext
import org.springframework.stereotype.Component

/**
 * SpEL 표현식 파싱 유틸리티 (LogicExecutor 평탄화 완료)
 *
 * <h3>#271 V5 Stateless Architecture 평가</h3>
 *
 * <p>{@code expressionCache}는 ConcurrentHashMap이지만 다음 이유로 인스턴스별 유지 가능:
 *
 * <ul>
 *   <li>읽기 전용 캐시: SpEL Expression 파싱 결과 캐싱 (변경 없음)</li>
 *   <li>인스턴스별 독립: 각 인스턴스가 동일한 Expression을 파싱해도 결과 동일</li>
 *   <li>비즈니스 영향 없음: 캐시 없어도 기능 동작, 성능 최적화용</li>
 * </ul>
 *
 * <h4>5-Agent Council 합의 (P1-2)</h4>
 *
 * <ul>
 *   <li>Blue (Architect): 읽기 전용 캐시로 Scale-out 안전</li>
 *   <li>Green (Performance): 인스턴스별 캐싱으로 JVM 내 최적화</li>
 * </ul>
 */
@Component
class CustomSpelParser(
    private val executor: LogicExecutor, // ✅ 지능형 실행기 주입
) {
    companion object {
        private val log = LoggerFactory.getLogger(CustomSpelParser::class.java)
    }
    private val parser: ExpressionParser = SpelExpressionParser()
    private val expressionCache: MutableMap<String, Expression> = ConcurrentHashMap()

    /** SpEL 표현식을 파싱하여 String으로 반환 */
    fun parse(joinPoint: ProceedingJoinPoint, expression: String): String = parseWithFallback(joinPoint, expression, joinPoint.signature.toShortString())

    /** ✅ parseWithFallback 평탄화 try-catch 대신 executeOrDefault를 사용하여 파싱 실패 시 안전하게 fallback 반환 */
    fun parseWithFallback(
        joinPoint: ProceedingJoinPoint,
        expression: String,
        fallback: String,
    ): String {
        val context = TaskContext.of("SpelParser", "ParseString", expression)

        return executor.executeOrDefault(
            {
                val evalContext = createEvaluationContext(joinPoint)

                // 1. 캐시에서 꺼내거나 없으면 파싱해서 저장 (변수명 수정: expression)
                val expr = expressionCache.computeIfAbsent(expression) { key -> parser.parseExpression(key) }

                // 2. 캐시된 expr 객체로 바로 평가 (성능 최적화)
                expr.getValue(evalContext)?.toString() ?: fallback
            },
            fallback,
            context,
        )
    }

    /** ✅ 제네릭 parse 평탄화 */
    fun <T> parse(joinPoint: ProceedingJoinPoint, expression: String, resultType: Class<T>, fallback: T): T {
        val context = TaskContext.of("SpelParser", "ParseGeneric", expression)

        return executor.executeOrDefault(
            {
                val evalContext = createEvaluationContext(joinPoint)
                parser.parseExpression(expression).getValue(evalContext, resultType) ?: fallback
            },
            fallback,
            context,
        )
    }

    /** ProceedingJoinPoint에서 메서드 파라미터를 추출하여 EvaluationContext 생성 */
    private fun createEvaluationContext(joinPoint: ProceedingJoinPoint): StandardEvaluationContext {
        val signature = joinPoint.signature as MethodSignature
        val context = StandardEvaluationContext()

        val parameterNames = signature.parameterNames
        val args = joinPoint.args

        if (parameterNames != null) {
            for (i in parameterNames.indices) {
                context.setVariable(parameterNames[i], args[i])
            }
        }

        return context
    }
}
