package maple.auth.login

import java.util.UUID
import java.util.concurrent.CompletableFuture
import maple.auth.fingerprint.FingerprintService
import maple.auth.jwt.JwtGeneratorService
import maple.auth.kafka.AuthEventPublisher
import maple.auth.kafka.PendingLoginRegistry
import maple.auth.session.SessionCacheService
import maple.expectation.core.auth.event.CharacterFetchRequest
import maple.expectation.core.domain.auth.Session
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

class LoginRejectedException(val statusCode: Int, message: String) : RuntimeException(message)

@Service
class LoginService(
    private val fingerprintService: FingerprintService,
    private val sessionCacheService: SessionCacheService,
    private val authEventPublisher: AuthEventPublisher,
    private val pendingLoginRegistry: PendingLoginRegistry,
    private val jwtGeneratorService: JwtGeneratorService,
) {
    fun login(apiKey: String, userIgn: String): CompletableFuture<LoginResult> {
        val request = CharacterFetchRequest(userIgn = userIgn, apiKey = apiKey)
        authEventPublisher.publishCharacterFetchRequest(request)

        return pendingLoginRegistry.register(request.eventId)
            .thenApply { response ->
                if (!response.success) {
                    log.warn("[Login] rejected: eventId={}, error={}", request.eventId, response.errorMessage)
                    throw LoginRejectedException(401, response.errorMessage ?: "Authentication failed")
                }
                if (response.accountId.isNullOrBlank()) {
                    throw LoginRejectedException(401, "Account ID not found in Nexon response")
                }
                if (userIgn !in response.characterOcidMap) {
                    log.warn("[Login] userIgn={} not found in Nexon character list", userIgn)
                    throw LoginRejectedException(401, "Character '$userIgn' not found in account")
                }
                response
            }
            .thenApply { response ->
                val fingerprint = fingerprintService.generate(requireNotNull(response.accountId) { "accountId missing" })

                val cached = sessionCacheService.findByFingerprint(fingerprint)
                if (cached != null) {
                    val token = jwtGeneratorService.generateToken(cached.sessionId, cached.fingerprint, cached.role, cached.userIgn)
                    log.info("[Login] cache hit: fingerprint={}", fingerprint)
                    return@thenApply LoginResult(token, cached.sessionId, fingerprint, cached.userIgn, cached.myOcids.size, cached = true)
                }

                val sessionId = UUID.randomUUID().toString()
                val myOcids = response.characterOcidMap.values.toSet()

                val session = Session.create(
                    sessionId = sessionId,
                    fingerprint = fingerprint,
                    userIgn = userIgn,
                    accountId = fingerprint,
                    apiKey = apiKey,
                    myOcids = myOcids,
                    role = Session.ROLE_USER,
                )
                sessionCacheService.save(session)

                val token = jwtGeneratorService.generateToken(sessionId, fingerprint, Session.ROLE_USER, userIgn)
                log.info("[Login] success: fingerprint={}, userIgn={}, characters={}", fingerprint, userIgn, myOcids.size)
                LoginResult(token, sessionId, fingerprint, userIgn, myOcids.size, cached = false)
            }
    }

    companion object {
        private val log = LoggerFactory.getLogger(LoginService::class.java)
    }
}
