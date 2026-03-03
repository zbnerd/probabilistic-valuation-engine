package maple.expectation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import maple.expectation.core.port.out.GameCharacterPort;
import maple.expectation.domain.model.character.CharacterId;
import maple.expectation.domain.model.character.GameCharacter;
import maple.expectation.domain.model.character.UserIgn;
import maple.expectation.web.controller.v1.GameCharacterControllerV1;
import maple.expectation.web.dto.response.CharacterResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * GameCharacterControllerV1 단위 테스트 (Issue #128, #194)
 *
 * <h4>경량 테스트 (CLAUDE.md Section 25)</h4>
 *
 * <p>순수 단위 테스트로 Controller 메서드만 직접 테스트합니다.
 *
 * <h4>ADR-005 Hexagonal Architecture</h4>
 *
 * <p>Controller가 GameCharacterPort 인터페이스에 의존하도록 리팩토링됨
 */
@Tag("unit")
class GameCharacterControllerV1Test {

  private GameCharacterPort gameCharacterPort;
  private GameCharacterControllerV1 controller;

  @BeforeEach
  void setUp() {
    gameCharacterPort = mock(GameCharacterPort.class);
    controller = new GameCharacterControllerV1(gameCharacterPort);
  }

  @Nested
  @DisplayName("캐릭터 조회 findCharacterByUserIgn")
  class FindCharacterByUserIgnTest {

    @Test
    @DisplayName("TC-128-01: 캐릭터 조회 성공 → CharacterResponse DTO 반환")
    void whenCharacterExists_shouldReturnDto() {
      // given
      GameCharacter character =
          GameCharacter.create(new UserIgn("TestUser"), new CharacterId("ocid-12345"));
      given(gameCharacterPort.getCharacterOrThrow("TestUser")).willReturn(character);

      // when
      ResponseEntity<CharacterResponse> response =
          controller.findCharacterByUserIgn("TestUser").join();

      // then
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(response.getBody()).isNotNull();
      assertThat(response.getBody().getUserIgn()).isEqualTo("TestUser");
      assertThat(response.getBody().getOcid()).isEqualTo("ocid-12345");
      assertThat(response.getBody().getLikeCount()).isEqualTo(0L);
    }

    @Test
    @DisplayName("TC-128-03: CharacterResponse는 내부 필드를 포함하지 않음")
    void shouldReturnDtoWithoutInternalFields() {
      // given
      GameCharacter character =
          GameCharacter.create(new UserIgn("TestUser"), new CharacterId("ocid-12345"));
      given(gameCharacterPort.getCharacterOrThrow("TestUser")).willReturn(character);

      // when
      ResponseEntity<CharacterResponse> response =
          controller.findCharacterByUserIgn("TestUser").join();

      // then - CharacterResponse Record는 userIgn, ocid, likeCount 등만 포함
      assertThat(response.getBody()).isNotNull();
      CharacterResponse dto = response.getBody();

      // Record 컴포넌트만 존재
      assertThat(dto.getUserIgn()).isNotNull();
      assertThat(dto.getOcid()).isNotNull();
      assertThat(dto.getLikeCount()).isNotNull();
    }

    @Test
    @DisplayName("초기 likeCount가 0인 캐릭터 조회")
    void whenInitialCharacter_shouldReturnZeroLikeCount() {
      // given
      GameCharacter character =
          GameCharacter.create(new UserIgn("NewUser"), new CharacterId("new-ocid"));
      given(gameCharacterPort.getCharacterOrThrow("NewUser")).willReturn(character);

      // when
      ResponseEntity<CharacterResponse> response =
          controller.findCharacterByUserIgn("NewUser").join();

      // then
      assertThat(response.getBody()).isNotNull();
      assertThat(response.getBody().getLikeCount()).isZero();
    }
  }
}
