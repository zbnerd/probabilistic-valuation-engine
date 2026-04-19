package maple.expectation.application.worker;

import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.application.service.expectation.queue.ExpectationCalculationTask;
import maple.expectation.application.service.expectation.queue.QueuePriority;
import maple.expectation.infrastructure.aop.annotation.TimedTask;
import maple.expectation.infrastructure.aop.context.WorkerMdcKeys;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.springframework.stereotype.Component;

/**
 * 계측된 계산 워커 (예제)
 *
 * <h3>구조</h3>
 *
 * <pre>{@code
 * Worker Loop (Runnable)
 *   ├── MDC 설정 (taskId, queueName, priority)
 *   ├── processTask(@TimedTask)     ← 전체 시간 측정
 *   │     ├── stageDelegate.fetch()       ← @TimedStage
 *   │     ├── stageDelegate.parse()       ← @TimedStage
 *   │     ├── stageDelegate.calculate()   ← @TimedStage
 *   │     ├── stageDelegate.persist()     ← @TimedStage
 *   │     ├── stageDelegate.upsertView()  ← @TimedStage
 *   │     └── stageDelegate.publishEvent()// @TimedStage
 *   └── MDC 정리 (finally)
 * }</pre>
 *
 * <h3>Self-invocation 회피</h3>
 *
 * <ul>
 *   <li>{@code processTask()}는 Spring 프록시를 통해 호출되어야 {@code @TimedTask}가 동작합니다. 따라서 {@code
 *       this.processTask()} 대신 인젝션된 자기 자신이나 별도 서비스 빈을 통해 호출해야 합니다.
 *   <li>아래 예제에서는 {@code runForPriority}가 인젝션된 {@code instrumentedSelf}의 {@code processTask()}를 호출하는
 *       구조입니다.
 * </ul>
 *
 * <h3>실제 적용 시</h3>
 *
 * <p>이 클래스는 예제입니다. 실제 적용 시:
 *
 * <ol>
 *   <li>{@code ExpectationCalculationWorker}에 MDC 설정/정리 로직 추가
 *   <li>{@code CalculationStageDelegate}에 실제 스테이지 로직 연결
 *   <li>{@code processTask()}에 {@code @TimedTask} 적용
 * </ol>
 */
@Slf4j
@Component
public class InstrumentedCalculationWorker {

  private final CalculationStageDelegate stageDelegate;
  private final LogicExecutor executor;

  /**
   * Self-invocation 회피를 위한 자기 참조.
   *
   * <p>Spring 프록시를 통과시키기 위해 {@code @Lazy} 자기 주입을 사용합니다. {@code this.processTask()}는 프록시를 우회하지만,
   * {@code self.processTask()}는 프록시를 통과하여 {@code @TimedTask}가 정상 동작합니다.
   */
  private final InstrumentedCalculationWorker self;

  public InstrumentedCalculationWorker(
      CalculationStageDelegate stageDelegate,
      LogicExecutor executor,
      @org.springframework.context.annotation.Lazy InstrumentedCalculationWorker self) {
    this.stageDelegate = stageDelegate;
    this.executor = executor;
    this.self = self;
  }

  /** 워커 루프: 태스크를 폴링하고 MDC 컨텍스트 설정 후 처리 */
  public void runForPriority(QueuePriority priority) {
    String queueName = resolveQueueName(priority);

    while (!Thread.currentThread().isInterrupted()) {
      ExpectationCalculationTask task = pollTask(priority);
      if (task == null) continue;

      try {
        WorkerMdcKeys.putTaskContext(task.getTaskId(), queueName, priority.name());
        task.setStartedAt(Instant.now());

        // self 참조로 프록시 통과 → @TimedTask 동작
        self.processTask(task);
      } catch (Exception e) {
        log.error("[Worker] Task failed: userIgn={}", task.getUserIgn(), e);
      } finally {
        WorkerMdcKeys.clearTaskContext();
      }
    }
  }

  /**
   * 전체 태스크 처리 (AOP로 시간 측정)
   *
   * <p>{@code @TimedTask}가 전체 처리 시간을 측정합니다. MDC에 설정된 taskId, queueName, priority가 자동으로 로그와 메트릭에
   * 포함됩니다.
   */
  @TimedTask("calculation")
  public void processTask(ExpectationCalculationTask task) {
    TaskContext context = TaskContext.of("Worker", "ProcessTask", task.getUserIgn());

    executor.executeVoidJava(
        () -> {
          // Stage 1-6: 각 스테이지는 별도 Bean을 통해 호출 → @TimedStage 동작
          Object rawData = stageDelegate.fetchData(task.getUserIgn());
          Object parsed = stageDelegate.parseData(rawData);
          Object result = stageDelegate.calculate(task.getUserIgn(), parsed);
          stageDelegate.persist(task.getUserIgn(), result);
          stageDelegate.upsertView(task.getUserIgn(), result);
          stageDelegate.publishEvent(task.getUserIgn(), result);
        },
        context);
  }

  private ExpectationCalculationTask pollTask(QueuePriority priority) {
    // 실제 구현: PGMQ에서 메시지 소비
    return null;
  }

  private String resolveQueueName(QueuePriority priority) {
    return switch (priority) {
      case HIGH -> "expectation_calc_high";
      case LOW -> "expectation_calc_low";
    };
  }
}
