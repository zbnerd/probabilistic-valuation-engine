package maple.auth.login

data class LoginResult(
    val token: String,
    val sessionId: String,
    val fingerprint: String,
    val userIgn: String,
    val characterCount: Int,
    val cached: Boolean,
)
