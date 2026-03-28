package maple.expectation.infrastructure.security.config

import maple.expectation.infrastructure.security.filter.JwtAuthenticationFilter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

/**
 * Spring Security 설정 (ADR-005, ADR-029)
 *
 * <p>Stateless JWT 기반 인증/인가 설정.
 *
 * <h3>핵심 정책</h3>
 * <ul>
 *   <li>CSRF 비활성화 (Stateless API)</li>
 *   <li>세션 미사용 (SESSIONLESS)</li>
 *   <li>JWT 필터 → @PreAuthorize 메서드 보안</li>
 * </ul>
 *
 * <h3>엔드포인트 접근 제어</h3>
 * <ul>
 *   <li>공개: /auth/**, /actuator/**, /swagger-ui/**, /v3/api-docs/**</li>
 *   <li>인증 필요: /api/**</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
class SecurityConfig(
    private val jwtAuthenticationFilter: JwtAuthenticationFilter,
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        return http
            .csrf { it.disable() }
            .cors { it.disable() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    // Auth endpoints
                    .requestMatchers("/auth/**").permitAll()
                    // Actuator
                    .requestMatchers("/actuator/**").permitAll()
                    // Swagger / OpenAPI
                    .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-resources/**").permitAll()
                    // Static resources
                    .requestMatchers("/favicon.ico", "/*.html", "/*.css", "/*.js").permitAll()
                    // Root
                    .requestMatchers(HttpMethod.GET, "/").permitAll()
                    // API endpoints require authentication
                    .requestMatchers("/api/**").authenticated()
                    // Everything else
                    .anyRequest().permitAll()
            }
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
            .build()
    }
}
