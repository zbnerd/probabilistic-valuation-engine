package maple.expectation.application.worker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.aop.annotation.TimedStage;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.springframework.stereotype.Component;

/**
 * 계산 스테이지 위임 빈 (예제)
 *
 * <h3>왜 별도 Bean인가?</h3>
 *
 * <p>Spring AOP는 프록시 기반으로 동작하므로, 같은 클래스 내에서
 * {@code this.method()} 호출(self-invocation)하면 AOP가 적용되지 않습니다.
 * 각 스테이지를 별도 Spring Bean의 <b>public 메서드</b>로 분리하면
 * 프록시를 정상적으로 통과하여 {@code @TimedStage}가 올바르게 동작합니다.
 *
 * <h3>Self-invocation 문제 회피 구조</h3>
 * <pre>{@code
 * // Worker (Bean A)              StageDelegate (Bean B)
 * // ┌──────────────────┐         ┌──────────────────────┐
 * // │ processTask()    │────────>│ fetch()  @TimedStage │  ← 프록시 통과 ✓
 * // │ @TimedTask       │────────>│ parse()  @TimedStage │  ← 프록시 통과 ✓
 * // │                  │────────>│ calculate()          │  ← 프록시 통과 ✓
 * // └──────────────────┘         └──────────────────────┘
 * }</pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CalculationStageDelegate {

    private final LogicExecutor executor;

    /**
     * Stage 1: 외부 API에서 캐릭터 데이터 조회
     */
    @TimedStage(value = "fetch", warnThresholdMs = 3000)
    public Object fetchData(String userIgn) {
        TaskContext context = TaskContext.of("Stage", "Fetch", userIgn);
        return executor.execute(
            () -> {
                // 실제 구현: NexonApiAdapter 등으로 외부 API 호출
                log.debug("[Stage:fetch] userIgn={}", userIgn);
                return null; // placeholder
            },
            context
        );
    }

    /**
     * Stage 2: 장비 데이터 파싱
     */
    @TimedStage(value = "parse", warnThresholdMs = 500)
    public Object parseData(Object rawData) {
        TaskContext context = TaskContext.of("Stage", "Parse");
        return executor.execute(
            () -> {
                log.debug("[Stage:parse] rawDataType={}", rawData != null ? rawData.getClass().getSimpleName() : "null");
                return null; // placeholder
            },
            context
        );
    }

    /**
     * Stage 3: 확률적 가치 계산
     */
    @TimedStage(value = "calculate", warnThresholdMs = 2000)
    public Object calculate(String userIgn, Object parsedData) {
        TaskContext context = TaskContext.of("Stage", "Calculate", userIgn);
        return executor.execute(
            () -> {
                log.debug("[Stage:calculate] userIgn={}", userIgn);
                return null; // placeholder
            },
            context
        );
    }

    /**
     * Stage 4: 계산 결과 MySQL 저장
     */
    @TimedStage(value = "persist", warnThresholdMs = 1000)
    public void persist(String userIgn, Object result) {
        TaskContext context = TaskContext.of("Stage", "Persist", userIgn);
        executor.executeVoidJava(
            () -> log.debug("[Stage:persist] userIgn={}", userIgn),
            context
        );
    }

    /**
     * Stage 5: MongoDB 읽기 모델 upsert
     */
    @TimedStage(value = "view_upsert", warnThresholdMs = 1000)
    public void upsertView(String userIgn, Object result) {
        TaskContext context = TaskContext.of("Stage", "ViewUpsert", userIgn);
        executor.executeVoidJava(
            () -> log.debug("[Stage:view_upsert] userIgn={}", userIgn),
            context
        );
    }

    /**
     * Stage 6: 이벤트 발행 (Redis Stream / Kafka)
     */
    @TimedStage(value = "event_publish", warnThresholdMs = 500)
    public void publishEvent(String userIgn, Object result) {
        TaskContext context = TaskContext.of("Stage", "EventPublish", userIgn);
        executor.executeVoidJava(
            () -> log.debug("[Stage:event_publish] userIgn={}", userIgn),
            context
        );
    }
}
