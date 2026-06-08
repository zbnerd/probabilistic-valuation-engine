package maple.auth.login

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import maple.auth.fingerprint.FingerprintService
import maple.auth.jwt.JwtGeneratorService
import maple.auth.kafka.AuthEventPublisher
import maple.auth.kafka.PendingLoginRegistry
import maple.auth.session.SessionCacheService
import maple.expectation.core.auth.event.CharacterFetchResponse
import maple.expectation.core.domain.auth.Session
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*

@ExtendWith(MockitoExtension::class)
class LoginServiceTest {
    @Mock private lateinit var fingerprintService: FingerprintService

    @Mock private lateinit var sessionCacheService: SessionCacheService

    @Mock private lateinit var authEventPublisher: AuthEventPublisher

    @Mock private lateinit var pendingLoginRegistry: PendingLoginRegistry

    @Mock private lateinit var jwtGeneratorService: JwtGeneratorService

    @Captor private lateinit var requestCaptor: ArgumentCaptor<maple.expectation.core.auth.event.CharacterFetchRequest>

    private fun createService() = LoginService(
        fingerprintService,
        sessionCacheService,
        authEventPublisher,
        pendingLoginRegistry,
        jwtGeneratorService,
    )

    @Test
    fun `cache hit after Nexon response returns cached session`() {
        val service = createService()
        val session = Session.create("s-1", "fp-1", "User1", "fp-1", "key", setOf("ocid-1"), "USER")
        whenever(fingerprintService.generate("acc-1")).thenReturn("fp-1")
        whenever(sessionCacheService.findByFingerprint("fp-1")).thenReturn(session)
        whenever(jwtGeneratorService.generateToken("s-1", "fp-1", "USER", "User1")).thenReturn("jwt-token")

        val response = CharacterFetchResponse(
            eventId = "evt-1",
            accountId = "acc-1",
            success = true,
            characterOcidMap = mapOf("User1" to "ocid-1"),
        )
        whenever(pendingLoginRegistry.register(any())).thenReturn(
            CompletableFuture.completedFuture(response),
        )

        val result = service.login("key", "User1").get()

        assertThat(result.cached).isTrue()
        assertThat(result.token).isEqualTo("jwt-token")
    }

    @Test
    fun `cache miss creates new session with fingerprint from accountId`() {
        val service = createService()
        whenever(fingerprintService.generate("acc-1")).thenReturn("fp-1")
        whenever(sessionCacheService.findByFingerprint("fp-1")).thenReturn(null)
        whenever(jwtGeneratorService.generateToken(any(), any(), any(), any())).thenReturn("jwt-token")

        val response = CharacterFetchResponse(
            eventId = "evt-1",
            accountId = "acc-1",
            success = true,
            characterOcidMap = mapOf("User1" to "ocid-1", "User2" to "ocid-2"),
        )
        whenever(pendingLoginRegistry.register(any())).thenReturn(
            CompletableFuture.completedFuture(response),
        )

        val result = service.login("key", "User1").get()

        assertThat(result.cached).isFalse()
        assertThat(result.fingerprint).isEqualTo("fp-1")
        assertThat(result.characterCount).isEqualTo(2)
        assertThat(result.userIgn).isEqualTo("User1")
        verify(authEventPublisher).publishCharacterFetchRequest(any())
        verify(sessionCacheService).save(any())
    }

    @Test
    fun `failed response throws 401`() {
        val service = createService()

        val response = CharacterFetchResponse(
            eventId = "evt-1",
            success = false,
            errorMessage = "Invalid API key",
        )
        whenever(pendingLoginRegistry.register(any())).thenReturn(
            CompletableFuture.completedFuture(response),
        )

        val future = service.login("key", "User1")
        val ex = org.assertj.core.api.Assertions.catchThrowable { future.get() }
        assertThat(ex).isInstanceOf(ExecutionException::class.java)
        val cause = ex!!.cause
        assertThat(cause).isInstanceOf(LoginRejectedException::class.java)
        assertThat((cause as LoginRejectedException).statusCode).isEqualTo(401)
        assertThat(cause.message).isEqualTo("Invalid API key")
    }

    @Test
    fun `userIgn not in character map throws 401`() {
        val service = createService()

        val response = CharacterFetchResponse(
            eventId = "evt-1",
            accountId = "acc-1",
            success = true,
            characterOcidMap = mapOf("OtherChar" to "ocid-1"),
        )
        whenever(pendingLoginRegistry.register(any())).thenReturn(
            CompletableFuture.completedFuture(response),
        )

        val future = service.login("key", "User1")
        assertThatThrownBy { future.get() }
            .isInstanceOf(ExecutionException::class.java)
            .hasCauseInstanceOf(LoginRejectedException::class.java)
    }
}
