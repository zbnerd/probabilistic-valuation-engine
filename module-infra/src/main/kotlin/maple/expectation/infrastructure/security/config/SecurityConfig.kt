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

/** Spring Security 설정 (ADR-005, ADR-029) - Stateless JWT 기반 인증/인가 */
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
