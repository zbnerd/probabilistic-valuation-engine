package maple.expectation.infrastructure.config

import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

/**
 * Legacy Executor Configuration — imports both Core and Infra configs.
 *
 * <p>module-app and other full-stack modules can continue using this.
 * Lightweight modules (external-api, synchronizer, calculator) should
 * import {@link CoreExecutorConfig} directly.
 *
 * @see CoreExecutorConfig
 * @see InfraExecutorConfig
 */
@Configuration
@Import(CoreExecutorConfig::class, InfraExecutorConfig::class)
class ExecutorConfig
