package maple.expectation.infrastructure.external.impl

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import maple.expectation.error.exception.CharacterNotFoundException
import maple.expectation.infrastructure.config.MaplestoryApiConfig
import maple.expectation.infrastructure.external.NexonApiClient
import maple.expectation.infrastructure.external.NexonAuthClient
import maple.nexon.client.byok.ByokNexonClient
import maple.nexon.client.byok.NexonAccount
import maple.nexon.client.byok.NexonCharacter
import maple.nexon.client.byok.NexonCharacterList
import maple.nexon.client.config.ByokNexonClientProperties
import maple.nexon.client.config.LegacyNexonApiProperties
import maple.nexon.client.config.NexonClientAutoConfiguration
import maple.nexon.client.config.SystemNexonClientProperties
import maple.nexon.client.failure.DecodeFailure
import maple.nexon.client.failure.InvalidCredential
import maple.nexon.client.failure.InvalidRequest
import maple.nexon.client.failure.NotFound
import maple.nexon.client.failure.RateLimited
import maple.nexon.client.failure.ResponseTooLarge
import maple.nexon.client.failure.Timeout
import maple.nexon.client.failure.TimeoutKind
import maple.nexon.client.failure.UpstreamUnavailable
import maple.nexon.client.model.NexonEndpointPurpose
import maple.nexon.client.model.NexonRequest
import maple.nexon.client.system.SystemKeyNexonClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.web.reactive.function.client.WebClient

class NexonCompatibilityAdapterTest {
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()

    @Test
    fun `compatibility configuration aliases the shared system WebClient under the legacy bean name`() {
        val shared = WebClient.builder().build()
        val config = MaplestoryApiConfig()

        assertThat(config.mapleWebClient(shared)).isSameAs(shared)
        assertThat(MaplestoryApiConfig::class.java.getAnnotation(Import::class.java).value)
            .contains(NexonClientAutoConfiguration::class)
        val method = MaplestoryApiConfig::class.java.getDeclaredMethod("mapleWebClient", WebClient::class.java)
        assertThat(method.getAnnotation(Bean::class.java).value).containsExactly("mapleWebClient")
        assertThat(method.parameterAnnotations.single().filterIsInstance<Qualifier>().single().value)
            .isEqualTo("nexonSystemWebClient")
        assertThat(RealNexonApiClient::class.java.interfaces).contains(NexonApiClient::class.java)

        val source = source("config/MaplestoryApiConfig.kt")
        assertThat(source).doesNotContain(
            "https://open.api.nexon.com",
            "HttpClient",
            "ConnectionProvider",
            "DefaultUriBuilderFactory",
            "WebClient.builder",
        )
    }

    @Test
    fun `system compatibility client maps every endpoint raw body and preserves requests`() {
        val systemClient = mock<SystemKeyNexonClient>()
        whenever(systemClient.fetch(any(), eq(SYSTEM_KEY))).thenAnswer { invocation ->
            val request = invocation.arguments[0] as NexonRequest
            CompletableFuture.completedFuture(SYSTEM_BODIES.getValue(request.purpose))
        }
        val client = RealNexonApiClient(systemClient, objectMapper, SYSTEM_KEY)

        assertThat(client.getOcidByCharacterName("용사").join().ocid).isEqualTo("ocid-1")
        val basic = client.getCharacterBasic("ocid-1").join()
        assertThat(basic.characterName).isEqualTo("Hero")
        assertThat(basic.characterLevel).isEqualTo(280)
        val equipment = client.getItemDataByOcid("ocid-2").join()
        assertThat(equipment.characterClass).isEqualTo("Warrior")
        assertThat(equipment.itemEquipment).singleElement().extracting("itemName").isEqualTo("Hat")
        val cube = client.getCubeHistory("ocid-3").join()
        val history = requireNotNull(cube.cubeHistory).single()
        assertThat(history.targetItem).isEqualTo("Hat")
        assertThat(history.afterPotentialOption).singleElement().extracting("value").isEqualTo("STR +12%")

        val requests = argumentCaptor<NexonRequest>()
        verify(systemClient, times(4)).fetch(requests.capture(), eq(SYSTEM_KEY))
        assertThat(requests.allValues).containsExactly(
            request(NexonEndpointPurpose.OCID_LOOKUP, "/maplestory/v1/id", "character_name", "용사"),
            request(NexonEndpointPurpose.CHARACTER_BASIC, "/maplestory/v1/character/basic", "ocid", "ocid-1"),
            request(NexonEndpointPurpose.ITEM_EQUIPMENT, "/maplestory/v1/character/item-equipment", "ocid", "ocid-2"),
            request(NexonEndpointPurpose.CUBE_HISTORY, "/maplestory/v1/history/cube", "ocid", "ocid-3"),
        )
    }

    @Test
    fun `system OCID not-found preserves the known app exception`() {
        val systemClient = mock<SystemKeyNexonClient>()
        val request = request(NexonEndpointPurpose.OCID_LOOKUP, "/maplestory/v1/id", "character_name", "Missing")
        whenever(systemClient.fetch(request, SYSTEM_KEY)).thenReturn(
            CompletableFuture.failedFuture(NotFound(request, 400, "OPENAPI00004")),
        )
        val client = RealNexonApiClient(systemClient, objectMapper, SYSTEM_KEY)

        assertThatThrownBy { client.getOcidByCharacterName("Missing").join() }
            .hasCauseInstanceOf(CharacterNotFoundException::class.java)
    }

    @Test
    fun `character mapper preserves nullable legacy fields`() {
        val mapper = NexonCharacterListMapper()
        val neutral = NexonCharacterList(
            listOf(
                NexonAccount(
                    accountId = null,
                    characters = listOf(NexonCharacter(null, null, null, null, 0)),
                ),
            ),
        )

        val legacy = mapper.toLegacy(neutral)

        val account = requireNotNull(legacy.accountList).single()
        val character = requireNotNull(account.characterList).single()
        assertThat(account.accountId).isNull()
        assertThat(character.ocid).isNull()
        assertThat(character.characterName).isNull()
        assertThat(character.worldName).isNull()
        assertThat(character.characterClass).isNull()
        assertThat(character.characterLevel).isZero()
    }

    @Test
    fun `auth compatibility maps populated success terminal failures and valid empty to legacy Optional`() {
        val byokClient = mock<ByokNexonClient>()
        val client = authClient(byokClient)
        val populated = NexonCharacterList(
            listOf(NexonAccount("account-1", listOf(NexonCharacter("ocid-1", "Hero", "Scania", "Hero", 280)))),
        )
        whenever(byokClient.getCharacterList(API_KEY)).thenReturn(CompletableFuture.completedFuture(populated))

        val success = client.getCharacterList(API_KEY)

        assertThat(success).isPresent
        val account = requireNotNull(success.orElseThrow().accountList).single()
        val character = requireNotNull(account.characterList).single()
        assertThat(account.accountId).isEqualTo("account-1")
        assertThat(character.ocid).isEqualTo("ocid-1")
        assertThat(character.characterName).isEqualTo("Hero")
        assertThat(character.characterLevel).isEqualTo(280)

        listOf(
            NexonCharacterList(emptyList()) to null,
            null to InvalidCredential(CHARACTER_LIST_REQUEST, 401, "OPENAPI00001"),
            null to NotFound(CHARACTER_LIST_REQUEST, 400, "OPENAPI00004"),
            null to InvalidRequest(CHARACTER_LIST_REQUEST, 400, "OPENAPI99999"),
        ).forEach { (result, failure) ->
            whenever(byokClient.getCharacterList(API_KEY)).thenReturn(
                failure?.let { CompletableFuture.failedFuture(it) }
                    ?: CompletableFuture.completedFuture(requireNotNull(result)),
            )
            assertThat(client.getCharacterList(API_KEY)).isEmpty
        }
    }

    @Test
    fun `auth compatibility propagates typed transient cap and decode failures unchanged`() {
        val byokClient = mock<ByokNexonClient>()
        val client = authClient(byokClient)
        val failures = listOf(
            RateLimited(CHARACTER_LIST_REQUEST, 429, "OPENAPI00007", Duration.ofSeconds(1)),
            Timeout(CHARACTER_LIST_REQUEST, TimeoutKind.RESPONSE),
            UpstreamUnavailable(CHARACTER_LIST_REQUEST, 503, "UPSTREAM"),
            ResponseTooLarge(CHARACTER_LIST_REQUEST),
            DecodeFailure(CHARACTER_LIST_REQUEST),
        )

        failures.forEach { failure ->
            whenever(byokClient.getCharacterList(API_KEY)).thenReturn(CompletableFuture.failedFuture(failure))

            assertThatThrownBy { client.getCharacterList(API_KEY) }.isSameAs(failure)
        }
    }

    @Test
    fun `auth facade timeout and null completion become sanitized typed failures`() {
        val byokClient = mock<ByokNexonClient>()
        val client = authClient(byokClient, callTimeoutSeconds = 0)
        whenever(byokClient.getCharacterList(API_KEY)).thenReturn(CompletableFuture())

        assertThatThrownBy { client.getCharacterList(API_KEY) }
            .isInstanceOfSatisfying(Timeout::class.java) { timeout ->
                assertThat(timeout.kind).isEqualTo(TimeoutKind.CALL)
                assertThat(timeout.cause).isNull()
            }

        val nullCompletion = CompletableFuture<NexonCharacterList>()
        nullCompletion.complete(null)
        whenever(byokClient.getCharacterList(API_KEY)).thenReturn(nullCompletion)
        assertThatThrownBy { client.getCharacterList(API_KEY) }
            .isInstanceOfSatisfying(DecodeFailure::class.java) { failure ->
                assertThat(failure.cause).isNull()
            }
    }

    @Test
    fun `legacy timeout properties feed shared profiles while explicit new keys win`() {
        val environment = StandardEnvironment().apply {
            propertySources.addFirst(
                MapPropertySource(
                    "test",
                    mapOf("nexon.byok-http-client.response-timeout-seconds" to "11"),
                ),
            )
        }
        val legacy = LegacyNexonApiProperties(Duration.ofSeconds(7), Duration.ofSeconds(8))

        val system = NexonClientAutoConfiguration.resolveSystemProperties(
            SystemNexonClientProperties(),
            legacy,
            environment,
        )
        val byok = NexonClientAutoConfiguration.resolveByokProperties(
            ByokNexonClientProperties(responseTimeoutSeconds = 11),
            legacy,
            environment,
        )

        assertThat(system.connectTimeoutMs).isEqualTo(7_000)
        assertThat(system.responseTimeoutSeconds).isEqualTo(8)
        assertThat(byok.connectTimeoutMs).isEqualTo(7_000)
        assertThat(byok.responseTimeoutSeconds).isEqualTo(11)
    }

    @Test
    fun `legacy auth interface has no validation method and adapter has no defaulting executor`() {
        assertThat(NexonAuthClient::class.java.declaredMethods.map { it.name })
            .containsExactly("getCharacterList")
        assertThat(source("external/impl/RealNexonAuthClient.kt")).doesNotContain(
            "LogicExecutor",
            "executeOrDefault",
            "WebClient",
            "responseBodyAsString",
            ".join(",
            ".get(",
        )
    }

    private fun authClient(
        byokClient: ByokNexonClient,
        callTimeoutSeconds: Long = 10,
    ): RealNexonAuthClient = RealNexonAuthClient(
        byokNexonClient = byokClient,
        characterListMapper = NexonCharacterListMapper(),
        properties = ByokNexonClientProperties(callTimeoutSeconds = callTimeoutSeconds),
    )

    private fun request(
        purpose: NexonEndpointPurpose,
        path: String,
        queryName: String,
        queryValue: String,
    ): NexonRequest = NexonRequest(purpose, path, mapOf(queryName to queryValue), path)

    private fun source(relative: String): String {
        val local = Path.of("src/main/kotlin/maple/expectation/infrastructure").resolve(relative)
        val root = Path.of("module-infra/src/main/kotlin/maple/expectation/infrastructure").resolve(relative)
        return Files.readString(if (Files.exists(local)) local else root)
    }

    private companion object {
        private const val API_KEY = "synthetic-byok-key"
        private const val SYSTEM_KEY = "synthetic-system-key"
        private val CHARACTER_LIST_REQUEST = NexonRequest(
            NexonEndpointPurpose.CHARACTER_LIST,
            "/maplestory/v1/character/list",
            emptyMap(),
            "/maplestory/v1/character/list",
        )
        private val SYSTEM_BODIES = mapOf(
            NexonEndpointPurpose.OCID_LOOKUP to """{"ocid":"ocid-1"}""".toByteArray(),
            NexonEndpointPurpose.CHARACTER_BASIC to
                """{"character_name":"Hero","character_level":280}""".toByteArray(),
            NexonEndpointPurpose.ITEM_EQUIPMENT to
                """{"character_class":"Warrior","item_equipment":[{"item_name":"Hat"}]}""".toByteArray(),
            NexonEndpointPurpose.CUBE_HISTORY to
                """{"cube_history":[{"target_item":"Hat","after_potential_option":[{"value":"STR +12%"}]}]}""".toByteArray(),
        )
    }
}
