package maple.expectation.controller.v1

import maple.expectation.core.port.out.GameCharacterPort
import maple.expectation.domain.model.character.GameCharacter
import maple.expectation.web.dto.response.CharacterResponse
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.CompletableFuture

/**
 * 캐릭터 API V1 (레거시) - ADR-005 이관
 *
 * Note: 좋아요 API는 V2로 이관됨 (인증 필요, Self-Like/중복 방지)
 *
 * **ADR-005 Hexagonal Architecture:**
 * - GameCharacterPort: 캐릭터 조회
 */
@RestController
@RequestMapping("/api/v1/characters")
class GameCharacterControllerV1(
    private val gameCharacterPort: GameCharacterPort
) {

    /** 캐릭터 정보 조회 */
    @GetMapping("/{userIgn}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    fun findCharacterByUserIgn(
        @PathVariable userIgn: String
    ): CompletableFuture<ResponseEntity<CharacterResponse>> {
        return CompletableFuture.supplyAsync {
            val character: GameCharacter = gameCharacterPort.getCharacterOrThrow(userIgn)
            ResponseEntity.ok(CharacterResponse.from(character))
        }
    }
}
