package maple.expectation.web.config

import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType
import io.swagger.v3.oas.annotations.info.Contact
import io.swagger.v3.oas.annotations.info.Info
import io.swagger.v3.oas.annotations.info.License
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.security.SecurityScheme
import io.swagger.v3.oas.annotations.servers.Server
import org.springframework.context.annotation.Configuration

/**
 * OpenAPI/Swagger Configuration (ADR-005 이관)
 *
 * API 문서화 및 인증 스키마 정의
 */
@Configuration
@OpenAPIDefinition(
    info = Info(
        title = "MapleExpectation API",
        version = "2.0.0",
        description = """
            메이플스토리 장비 기대값 계산 API

            ## 인증 방식
            - **BYOK (Bring Your Own Key)**: Nexon API Key로 로그인
            - **JWT Bearer Token**: 로그인 후 발급받은 토큰 사용

            ## 주요 기능
            - 캐릭터 장비 조회
            - 큐브 기대값 계산
            - 캐릭터 좋아요 (인증 필요)
        """,
        contact = Contact(
            name = "MapleExpectation",
            url = "https://github.com/geeksqualo/MapleExpectation"
        ),
        license = License(name = "MIT License", url = "https://opensource.org/licenses/MIT")
    ),
    servers = [
        Server(url = "http://localhost:8080", description = "Local Development"),
        Server(url = "https://api.maple-expectation.com", description = "Production")
    ],
    security = [SecurityRequirement(name = "bearerAuth")]
)
@SecurityScheme(
    name = "bearerAuth",
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT",
    description = "JWT 인증 토큰 (POST /auth/login으로 발급)"
)
class OpenApiConfig
