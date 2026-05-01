package maple.expectation.infrastructure.external.impl

import java.util.concurrent.CompletableFuture
import maple.expectation.error.exception.CharacterNotFoundException
import maple.expectation.infrastructure.config.TimeoutProperties
import maple.expectation.infrastructure.external.NexonApiClient
import maple.expectation.infrastructure.external.dto.v2.CharacterBasicResponse
import maple.expectation.infrastructure.external.dto.v2.CharacterOcidResponse
import maple.expectation.infrastructure.external.dto.v2.CubeHistoryResponse
import maple.expectation.infrastructure.external.dto.v2.EquipmentResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Mono

/**
 * 실제 Nexon API 클라이언트 구현체
 *
 * <p>Real implementation of Nexon API client using WebClient
 */
@Profile("!chaos")
@Component("realNexonApiClient")
@org.springframework.beans.factory.annotation.Qualifier("realNexonApiClient")
class RealNexonApiClient(
    @Qualifier("mapleWebClient")
    private val mapleWebClient: WebClient,
    private val timeoutProperties: TimeoutProperties,
) : NexonApiClient {

    private val logger = LoggerFactory.getLogger(RealNexonApiClient::class.java)

    @Value("\${nexon.api.key}")
    private lateinit var apiKey: String

    /**
     * 캐릭터 이름으로 OCID 조회 (비동기)
     *
     * <p>Issue #195: .block() → .toFuture() 전환으로 Reactor 체인 내 블로킹 제거
     *
     * <p>Issue #196: timeout + onErrorResume 패턴으로 에러 본문 로깅
     */
    override fun getOcidByCharacterName(characterName: String): CompletableFuture<CharacterOcidResponse> {
        logger.info("[NexonApi] OCID lookup: characterName={}", characterName)
        return mapleWebClient
            .get()
            .uri { uriBuilder ->
                uriBuilder
                    .path("/maplestory/v1/id")
                    .queryParam("character_name", characterName)
                    .build()
            }
            .header("x-nxopen-api-key", apiKey)
            .retrieve()
            .bodyToMono(CharacterOcidResponse::class.java)
            .onErrorResume(
                WebClientResponseException::class.java,
            ) { ex ->
                logger.warn(
                    "[NexonApi] OCID lookup failed. Status: {}, Body: {}",
                    ex.statusCode,
                    ex.responseBodyAsString,
                )
                if (isInvalidOcidLookupParameter(ex)) {
                    return@onErrorResume Mono.error(CharacterNotFoundException(characterName))
                }
                Mono.error(ex)
            }
            .timeout(timeoutProperties.apiCall)
            .toFuture()
    }

    /**
     * OCID로 캐릭터 기본 정보 조회 (비동기)
     *
     * <p>Nexon API /maplestory/v1/character/basic 호출
     */
    override fun getCharacterBasic(ocid: String): CompletableFuture<CharacterBasicResponse> {
        logger.info("[NexonApi] Character basic info request: ocid={}", ocid)
        return mapleWebClient
            .get()
            .uri { uriBuilder ->
                uriBuilder.path("/maplestory/v1/character/basic")
                    .queryParam("ocid", ocid)
                    .build()
            }
            .header("x-nxopen-api-key", apiKey)
            .retrieve()
            .bodyToMono(CharacterBasicResponse::class.java)
            .timeout(timeoutProperties.apiCall)
            .toFuture()
    }

    override fun getItemDataByOcid(ocid: String): CompletableFuture<EquipmentResponse> {
        logger.info("[NexonApi] Equipment data request (Cache Miss): ocid={}", ocid)
        return mapleWebClient
            .get()
            .uri { uriBuilder ->
                uriBuilder
                    .path("/maplestory/v1/character/item-equipment")
                    .queryParam("ocid", ocid)
                    .build()
            }
            .header("x-nxopen-api-key", apiKey)
            .retrieve()
            .bodyToMono(EquipmentResponse::class.java)
            .timeout(timeoutProperties.apiCall)
            .toFuture()
    }

    /**
     * OCID로 큐브 사용 내역 조회 (비동기)
     *
     * <p>Nexon API /maplestory/v1/history/cube 호출
     */
    override fun getCubeHistory(ocid: String): CompletableFuture<CubeHistoryResponse> {
        logger.info("[NexonApi] Cube history request: ocid={}", ocid)
        return mapleWebClient
            .get()
            .uri { uriBuilder ->
                uriBuilder.path("/maplestory/v1/history/cube")
                    .queryParam("ocid", ocid)
                    .build()
            }
            .header("x-nxopen-api-key", apiKey)
            .retrieve()
            .bodyToMono(CubeHistoryResponse::class.java)
            .timeout(timeoutProperties.apiCall)
            .toFuture()
    }

    private fun isInvalidOcidLookupParameter(ex: WebClientResponseException): Boolean = ex.statusCode.value() == 400 && ex.responseBodyAsString.contains("OPENAPI00004")
}
