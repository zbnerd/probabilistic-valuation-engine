package maple.expectation.infrastructure.config

import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

/**
 * Legacy Executor Configuration — imports Core, Infra, and RestController configs.
 *
 * <p>module-app and other full-stack modules can continue using this.
 * Lightweight modules (external-api, synchronizer, calculator) should
 * import {@link CoreExecutorConfig} directly.
 * module-rest-controller imports {@link RestControllerExecutorConfig} directly.
 *
 * @see CoreExecutorConfig
 * @see InfraExecutorConfig
 * @see RestControllerExecutorConfig
 */
@Configuration
@Import(CoreExecutorConfig::class, InfraExecutorConfig::class, RestControllerExecutorConfig::class)
class ExecutorConfig
