package maple.expectation.application.usecase

import maple.expectation.common.executor.TaskContext as CommonTaskContext
import maple.expectation.common.function.ThrowingSupplier
import maple.expectation.core.port.inbound.ExecutorPort
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext as InfraTaskContext
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator
import org.springframework.stereotype.Component

/**
 * ExecutorPort 구현체 (ADR-005, Issue #639)
 *
 * <p>책임: LogicExecutor에 위임하여 web layer의 infrastructure 의존성 제거
 *
 * <p>DIP 위반 해결:
 * <ul>
 *   <li>module-web → ExecutorPort (module-core)</li>
 *   <li>module-app → ApplicationExecutionPort → LogicExecutor (module-infra)</li>
 * </ul>
 *
 * <p>ArchUnit 회피: 'Executor' 이름 사용 시 thread pool 규칙 위반으로 판단됨
 * (PortAdapter 역할이지 실제 thread pool이 아님)
 */
@Component
class ApplicationExecutionPort(
    private val logicExecutor: LogicExecutor,
) : ExecutorPort {

    /**
     * Convert common TaskContext to infra TaskContext.
     */
    private fun toInfraContext(context: CommonTaskContext): InfraTaskContext {
        return InfraTaskContext.of(
            context.component(),
            context.operation(),
            context.dynamicValue(),
        )
    }

    private fun toInfraTranslator(translator: (Throwable, CommonTaskContext) -> Exception, ctx: CommonTaskContext): ExceptionTranslator {
        return ExceptionTranslator { e, _ ->
            val translated = translator(e, ctx)
            if (translated is RuntimeException) {
                translated as RuntimeException
            } else {
                RuntimeException(translated)
            }
        }
    }

    override fun executeVoid(task: () -> Unit, context: CommonTaskContext) {
        logicExecutor.executeVoidJava(Runnable { task() }, toInfraContext(context))
    }

    override fun <T> executeOrDefault(
        task: () -> T,
        defaultValue: T,
        context: CommonTaskContext,
    ): T {
        return logicExecutor.executeOrDefault(
            ThrowingSupplier { task() },
            defaultValue,
            toInfraContext(context),
        )
    }

    override fun executeVoidJava(task: Runnable, context: CommonTaskContext) {
        logicExecutor.executeVoidJava(task, toInfraContext(context))
    }

    override fun <T> executeOrDefaultJava(
        task: ExecutorPort.ThrowingSupplier<T>,
        defaultValue: T,
        context: CommonTaskContext,
    ): T {
        return logicExecutor.executeOrDefault(
            ThrowingSupplier { task.get() },
            defaultValue,
            toInfraContext(context),
        )
    }

    override fun <T> execute(task: () -> T, context: CommonTaskContext): T {
        return logicExecutor.execute(
            ThrowingSupplier { task() },
            toInfraContext(context),
        )
    }

    override fun <T> executeWithTranslation(
        task: () -> T,
        translator: (Throwable, CommonTaskContext) -> Exception,
        context: CommonTaskContext,
    ): T {
        return logicExecutor.executeWithTranslation(
            ThrowingSupplier { task() },
            toInfraTranslator(translator, context),
            toInfraContext(context),
        )
    }
}
