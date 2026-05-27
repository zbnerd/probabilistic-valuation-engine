package maple.expectation.core.auth

import java.util.Optional

interface JwtParserPort {
    fun parseToken(token: String?): Optional<JwtPayload>
    fun validateToken(token: String?): Boolean
}
