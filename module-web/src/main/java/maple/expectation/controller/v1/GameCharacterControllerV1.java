package maple.expectation.controller.v1;

import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import maple.expectation.core.port.out.GameCharacterPort;
import maple.expectation.domain.model.character.GameCharacter;
import maple.expectation.web.dto.response.CharacterResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 캐릭터 API V1 (레거시) - ADR-005 이관
 *
 * <p>Note: 좋아요 API는 V2로 이관됨 (인증 필요, Self-Like/중복 방지)
 *
 * <p>ADR-005 Hexagonal Architecture:
 *
 * <ul>
 *   <li>GameCharacterPort: 캐릭터 조회
 * </ul>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/characters")
public class GameCharacterControllerV1 {

  private final GameCharacterPort gameCharacterPort;

  /** 캐릭터 정보 조회 */
  @GetMapping("/{userIgn}")
  @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
  public CompletableFuture<ResponseEntity<CharacterResponse>> findCharacterByUserIgn(
      @PathVariable String userIgn) {
    return CompletableFuture.supplyAsync(
        () -> {
          GameCharacter character = gameCharacterPort.getCharacterOrThrow(userIgn);
          return ResponseEntity.ok(CharacterResponse.fromDomainModel(character));
        });
  }
}
