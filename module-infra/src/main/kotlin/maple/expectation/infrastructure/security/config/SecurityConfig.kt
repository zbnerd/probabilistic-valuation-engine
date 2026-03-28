package maple.expectation.infrastructure.security.config

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletResponse
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
 * Spring Security 설정 (ADR-005, ADR-029) - Stateless JWT 기반 인증/인가
 *
 * <p>CSRF 보호 비활성화 사유: 이 API는 JWT를 사용하는 stateless 한 REST API입니다.
 * 세션 쿠키를 사용하지 않으며, 모든 인증은 Authorization 헤더의 JWT 토큰을 통해 이루어집니다.
 * CSRF 공격은 브라우저의 쿠키 기반 인증을 exploit하므로, 쿠키를 사용하지 않는 JWT API에는 적용되지 않습니다.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
class SecurityConfig(
    private val jwtAuthenticationFilter: JwtAuthenticationFilter,
    private val meterRegistry: MeterRegistry,
) {

    private val unauthorizedCounter = Counter.builder("auth.failure.unauthorized")
        .description("Count of 401 unauthorized authentication failures")
        .register(meterRegistry)

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        return http
            // CSRF 비활성화: stateless JWT API, 세션 쿠키 미사용
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
            .exceptionHandling { it.authenticationEntryPoint { _, response, _ ->
                unauthorizedCounter.increment()
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")
            } }
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
            .build()
    }
}
