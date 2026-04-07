package maple.expectation.config;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.application.worker.PriorityCalculationExecutor;
import maple.expectation.infrastructure.executor.CheckedLogicExecutor;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * V5 CQRS: Worker Pool Configuration
 *
 * <p>Starts the calculation worker pool on application startup and manages graceful shutdown.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "v5.enabled", havingValue = "true", matchIfMissing = false)
public class V5Config {

  private final PriorityCalculationExecutor executor;
  private final LogicExecutor logicExecutor;
  private final CheckedLogicExecutor checkedLogicExecutor;

  public V5Config(
      PriorityCalculationExecutor executor,
      LogicExecutor logicExecutor,
      @Qualifier("checkedLogicExecutor") CheckedLogicExecutor checkedLogicExecutor) {
    this.executor = executor;
    this.logicExecutor = logicExecutor;
    this.checkedLogicExecutor = checkedLogicExecutor;
  }

  @jakarta.annotation.PostConstruct
  public void startWorkerPool() {
    TaskContext context = TaskContext.of("V5-Config", "StartWorkerPool");

    checkedLogicExecutor.executeUncheckedVoid(
        () -> {
          log.info("[V5-Config] V5 CQRS initialized (PGMQ workers handle consumption, polling workers disabled)");
          // Legacy polling workers disabled — PGMQ workers (ExpectationCalcWorker, ExpectationCalcLowWorker)
          // now handle message consumption via @Scheduled pgmqClient.read()
          // executor.start();
        },
        context,
        e -> new IllegalStateException("V5 CQRS worker pool startup failed", e));
  }

  @PreDestroy
  public void shutdownWorkerPool() {
    TaskContext context = TaskContext.of("V5-Config", "ShutdownWorkerPool");

    logicExecutor.executeVoid(
        () -> {
          log.info("[V5-Config] Shutting down worker pool...");
          executor.stop();
          log.info("[V5-Config] Worker pool shut down gracefully");
        },
        context);
  }
}
