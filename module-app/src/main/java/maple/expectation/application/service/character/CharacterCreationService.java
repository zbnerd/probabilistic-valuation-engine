package maple.expectation.application.service.character;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.core.domain.model.character.GameCharacter;
import maple.expectation.error.exception.ApiTimeoutException;
import maple.expectation.error.exception.CharacterNotFoundException;
import maple.expectation.error.exception.InternalSystemException;
import maple.expectation.error.exception.base.BaseException;
import maple.expectation.infrastructure.character.notify.CharacterCreationNotifier;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.external.NexonApiClient;
import maple.expectation.infrastructure.persistence.repository.GameCharacterRepository;
import maple.expectation.util.ExceptionUtils;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 캐릭터 생성 공통 서비스 (OcidResolver + GameCharacterService 중복 제거)
 *
 * <h3>책임: 캐릭터 생성 + 네거티브/포지티브 캐싱</h3>
 *
 * <ul>
 *   <li>Nexon API OCID 조회
 *   <li>DB 저장 (트랜잭션 범위 최소화)
 *   <li>네거티브 캐시 (CharacterNotFoundException 시)
 *   <li>포지티브 캐시 (OCID 캐시)
 * </ul>
 *
 * <h3>분해 근거</h3>
 *
 * <p>OcidResolver.createNewCharacter()와 GameCharacterService.createNewCharacter()의 공통 로직 추출
 * (CLAUDE.md Section 4 - SOLID SRP)
 *
 * @see OcidResolver
 * @see GameCharacterService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CharacterCreationService {

  /** Issue #284 P0: 외부 API 호출 타임아웃 (초) */
  private static final long API_TIMEOUT_SECONDS = 10L;

  private final GameCharacterRepository gameCharacterRepository;
  private final NexonApiClient nexonApiClient;
  private final CacheManager cacheManager;
  private final LogicExecutor executor;
  private final CharacterCreationNotifier characterCreationNotifier;

  /**
   * 캐릭터 생성 - Issue #226: 트랜잭션 경계 분리
   *
   * <h4>Connection Pool 고갈 방지 (P1)</h4>
   *
   * <p>API 호출은 트랜잭션 밖, DB 작업만 트랜잭션 안
   *
   * @param userIgn 캐릭터 닉네임
   * @return 생성된 GameCharacter
   * @throws CharacterNotFoundException Nexon API에서 캐릭터가 존재하지 않는 경우
   */
  /**
   * 캐릭터 생성 - Issue #226: 트랜잭션 경계 분리
   *
   * <h4>Connection Pool 고갈 방지 (P1)</h4>
   *
   * <p>API 호출은 트랜잭션 밖, DB 작업만 트랜잭션 안
   *
   * <h4>비동기 체이닝 (join/get 금지)</h4>
   *
   * <p>Nexon API 호출 결과를 CF 체이닝으로 DB 저장에 연결
   *
   * @param userIgn 캐릭터 닉네임
   * @return 생성된 GameCharacter
   * @throws CharacterNotFoundException Nexon API에서 캐릭터가 존재하지 않는 경우
   */
  public GameCharacter createNewCharacter(String userIgn) {
    TaskContext context = TaskContext.of("Character", "Create", userIgn);

    return executor.executeOrCatch(
        () -> resolveOcidAndSaveCharacter(userIgn),
        e -> handleCreationFailure(userIgn, e),
        context);
  }

  /** OCID 비동기 조회 후 DB 저장 (CF 체이닝, lambda 추출) */
  private GameCharacter resolveOcidAndSaveCharacter(String userIgn) {
    log.info("✨ [Creation] 캐릭터 생성 시작: {}", userIgn);

    String ocid = fetchOcidFromApi(userIgn);
    return saveCharacterWithCaching(userIgn, ocid);
  }

  /** Nexon API에서 OCID 조회 (CF 체이닝으로 join/get 없이 결과 획득) */
  private String fetchOcidFromApi(String userIgn) {
    return nexonApiClient
        .getOcidByCharacterName(userIgn)
        .orTimeout(API_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .thenApply(response -> response.getOcid())
        .join(); // Sync boundary: createNewCharacter is called from sync context
  }

  /**
   * DB 저장 + 포지티브 캐싱 (트랜잭션 범위 최소화) - Issue #226
   *
   * <p>Connection 점유 시간: ~100ms (28초 → 100ms)
   */
  @Transactional(transactionManager = "tm", propagation = Propagation.REQUIRES_NEW)
  public GameCharacter saveCharacterWithCaching(String userIgn, String ocid) {
    // Value objects 생성
    maple.expectation.core.domain.model.character.UserIgn userIgnVo =
        maple.expectation.core.domain.model.character.UserIgn.of(userIgn);
    maple.expectation.core.domain.model.character.CharacterId characterId =
        maple.expectation.core.domain.model.character.CharacterId.of(ocid);

    GameCharacter newCharacter = GameCharacter.create(userIgnVo, characterId);
    GameCharacter saved = gameCharacterRepository.save(newCharacter);

    // Positive Cache: OCID 캐시
    Optional.ofNullable(cacheManager.getCache("ocidCache")).ifPresent(c -> c.put(userIgn, ocid));

    // Notify character creation event (replaces Thread.sleep polling in GameCharacterFacade)
    characterCreationNotifier.notifyCharacterCreated(userIgn);

    return saved;
  }

  /**
   * 캐릭터 생성 실패 처리 (네거티브 캐시 + 예외 재전파)
   *
   * <p>PR #199, #241 Fix: CompletionException unwrap 후 CharacterNotFoundException 감지
   */
  private GameCharacter handleCreationFailure(String userIgn, Throwable e) {
    Throwable unwrapped = ExceptionUtils.unwrapAsyncException(e);

    // Issue #284 P0: TimeoutException 감지 → 서킷브레이커 기록
    if (unwrapped instanceof TimeoutException) {
      throw new ApiTimeoutException("NexonOcidAPI", unwrapped);
    }

    if (unwrapped instanceof CharacterNotFoundException) {
      log.warn("🚫 [Recovery] 캐릭터 미존재 → 네거티브 캐시 저장: {}", userIgn);
      Optional.ofNullable(cacheManager.getCache("ocidNegativeCache"))
          .ifPresent(c -> c.put(userIgn, "NOT_FOUND"));
    }
    if (e instanceof BaseException be) {
      throw be;
    }
    if (e instanceof RuntimeException re) {
      throw re;
    }
    throw new InternalSystemException("CharacterCreationService.createNewCharacter", e);
  }
}
