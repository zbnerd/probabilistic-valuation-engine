package maple.expectation.infrastructure.config

import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * Jackson JSON 파싱 설정
 *
 * <h3>KotlinModule 등록</h3>
 *
 * <p>Kotlin data class 역직렬화를 위해 KotlinModule을 ObjectMapper에 직접 등록.
 * Spring Boot auto-configuration의 Module 감지가 신뢰할 수 없어 직접 등록 방식 사용.
 *
 * <h3>보안 제약 (#266 P1-4: DoS 방어)</h3>
 *
 * <ul>
 *   <li>Deeply Nested JSON: 50레벨 이상 중첩 시 거부</li>
 *   <li>Large String: 100KB 이상 문자열 필드 거부</li>
 *   <li>Long Property Name: 256자 이상 속성명 거부</li>
 * </ul>
 */
@Configuration
class JacksonConfig {

    companion object {
        private const val MAX_DEPTH = 50
        private const val MAX_STRING_LENGTH = 100_000
        private const val MAX_NAME_LENGTH = 256
    }

    /**
     * Primary ObjectMapper with KotlinModule explicitly registered
     *
     * <p>Spring Boot auto-configuration의 Module bean 감지가 unreliable하여
     * 직접 ObjectMapper를 생성하고 KotlinModule + JavaTimeModule을 등록.
     */
    @Bean
    @Primary
    fun objectMapper(): ObjectMapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .also { om ->
            om.factory.setStreamReadConstraints(
                StreamReadConstraints.builder()
                    .maxNestingDepth(MAX_DEPTH)
                    .maxStringLength(MAX_STRING_LENGTH)
                    .maxNameLength(MAX_NAME_LENGTH)
                    .build(),
            )
        }

    /**
     * Builder customizer for Spring MVC serialization settings
     */
    @Bean
    fun jsonCustomizer(): Jackson2ObjectMapperBuilderCustomizer = Jackson2ObjectMapperBuilderCustomizer { builder ->
        builder.modules(KotlinModule.Builder().build(), JavaTimeModule())
    }
}
