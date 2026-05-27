package maple.expectation.core.auth

interface JwtGeneratorPort {
    fun generateToken(payload: JwtPayload): String
    fun generateToken(sessionId: String, fingerprint: String, role: String, userIgn: String = ""): String
}
