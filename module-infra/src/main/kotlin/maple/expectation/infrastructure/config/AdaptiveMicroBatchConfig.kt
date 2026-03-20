package maple.expectation.infrastructure.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * Adaptive Micro-Batching 설정 활성화
 *
 * <p>Issue #588, #599: GameCharacter, L2Cache 마이크로 배칭을 위한 설정 프로퍼티 활성화
 *
 * @see AdaptiveMicroBatchProperties
 * @see maple.expectation.infrastructure.batch.GameCharacterMicroBatchAdapter
 * @see maple.expectation.infrastructure.batch.L2CacheMicroBatchAdapter
 */
@Configuration
@EnableConfigurationProperties(AdaptiveMicroBatchProperties::class)
class AdaptiveMicroBatchConfig
