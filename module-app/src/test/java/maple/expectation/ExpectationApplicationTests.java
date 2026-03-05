package maple.expectation;

import maple.expectation.application.service.character.GameCharacterService;
import maple.expectation.config.GlobalTestConfig;
import maple.expectation.core.domain.model.character.CharacterId;
import maple.expectation.core.domain.model.character.GameCharacter;
import maple.expectation.core.domain.model.character.UserIgn;
import maple.expectation.infrastructure.external.impl.RealNexonApiClient;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
@Tag("integration")
@Import(GlobalTestConfig.class)
class ExpectationApplicationTests {

  @Autowired GameCharacterService gameCharacterService;

  @MockitoBean RealNexonApiClient nexonApiClient;

  @Test
  void 캐릭터ocid생성() {
    // [Given]
    // 💡 [수정 포인트] 도메인 모델의 팩토리 메서드 사용
    // UserIgn과 CharacterId는 값 객체로 감싸져 있음
    GameCharacter gameCharacter =
        GameCharacter.create(new UserIgn("Geek"), new CharacterId("0123456789abcdef"));

    // [When]
    gameCharacterService.saveCharacter(gameCharacter);

    // [Then]
    Assertions.assertThat(gameCharacter.getUserIgn().value()).isEqualTo("Geek");
    Assertions.assertThat(gameCharacter.getOcid()).isEqualTo("0123456789abcdef");
  }
}
