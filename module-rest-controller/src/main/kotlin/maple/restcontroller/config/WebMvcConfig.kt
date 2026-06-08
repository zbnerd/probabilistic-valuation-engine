package maple.restcontroller.config

import maple.restcontroller.auth.JwtAuthInterceptor
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebMvcConfig(
    private val jwtAuthInterceptor: JwtAuthInterceptor,
) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(jwtAuthInterceptor)
            .addPathPatterns("/api/v6/characters/*/like", "/api/v6/characters/*/like/status")
    }
}
