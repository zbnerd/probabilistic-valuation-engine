package maple.expectation.application.service.character;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.application.worker.CharacterAsyncService;
import maple.expectation.core.domain.model.character.GameCharacter;
import maple.expectation.domain.repository.GameCharacterRepository;
import maple.expectation.error.exception.CharacterNotFoundException;
import maple.expectation.infrastructure.aop.annotation.ObservedTransaction;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.external.NexonApiClient;
import maple.expectation.infrastructure.external.dto.v2.CharacterBasicResponse;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 캐릭터 도메인 서비스
 *
 * <p>책임:
 *
 * <ul>
 *   <li>캐릭터 조회/생성
 *   <li>Negative/Positive 캐싱
 *   <li>좋아요 버퍼 동기화 지원 (getCharacterForUpdate)
 * </ul>
 *
 * <p>Note: 좋아요 API는 CharacterLikeService로 이관됨 (Self-Like/중복 방지 포함)
 */
@Slf4j
@Service
public class GameCharacterService {

  /** Issue #284 P0: 외부 API 호출 타임아웃 (초) */
  private static final long API_TIMEOUT_SECONDS = 10L;

  private final GameCharacterRepository gameCharacterRepository;
  private final NexonApiClient nexonApiClient;
  private final CacheManager cacheManager;
  private final LogicExecutor executor;
  private final CharacterCreationService characterCreationService;
  private final CharacterAsyncService characterAsyncService;

  public GameCharacterService(
      GameCharacterRepository gameCharacterRepository,
      NexonApiClient nexonApiClient,
      CacheManager cacheManager,
      LogicExecutor executor,
      CharacterCreationService characterCreationService,
      CharacterAsyncService characterAsyncService) {
    this.gameCharacterRepository = gameCharacterRepository;
    this.nexonApiClient = nexonApiClient;
    this.cacheManager = cacheManager;
    this.executor = executor;
    this.characterCreationService = characterCreationService;
    this.characterAsyncService = characterAsyncService;
  }

  /** ⚡ [Negative Cache 확인] executeOrDefault를 사용하여 캐시 존재 여부 및 타입 캐스팅 노이즈 제거 */
  public boolean isNonExistent(String userIgn) {
    String cleanIgn = userIgn.trim();
    return executor.executeOrDefault(
        () -> {
          Cache cache = cacheManager.getCache("ocidNegativeCache");
          return cache != null && "NOT_FOUND".equals(cache.get(cleanIgn, String.class));
        },
        false,
        TaskContext.of("Cache", "CheckNegative", cleanIgn));
  }

  /** ⚡ [N+1 해결] 캐릭터와 장비를 한방에 가져옵니다. */
  public Optional<GameCharacter> getCharacterIfExist(String userIgn) {
    String cleanIgn = userIgn.trim();
    return executor.execute(
        () -> Optional.ofNullable(gameCharacterRepository.findByUserIgn(cleanIgn)),
        TaskContext.of("DB", "FindWithEquipment", cleanIgn));
  }

  /**
   * 캐릭터 생성 (CharacterCreationService 위임 + 기본 정보 보강)
   *
   * <h4>Issue #226: Connection Pool 고갈 방지</h4>
   *
   * <p>CharacterCreationService에서 트랜잭션 경계 분리 적용
   *
   * @see CharacterCreationService#createNewCharacter(String)
   */
  @ObservedTransaction("service.v2.GameCharacterService.createNewCharacter")
  public GameCharacter createNewCharacter(String userIgn) {
    GameCharacter created = characterCreationService.createNewCharacter(userIgn.trim());
    return enrichCharacterBasicInfo(created);
  }

  @Transactional("transactionManager")
  public String saveCharacter(GameCharacter character) {
    return executor.execute(
        () -> {
          GameCharacter saved = gameCharacterRepository.save(character);
          return saved.getUserIgn().value();
        },
        TaskContext.of("DB", "SaveCharacter", character.getUserIgn().value()));
  }

  public GameCharacter getCharacterOrThrow(String userIgn) {
    return executor.execute(
        () -> {
          GameCharacter found = gameCharacterRepository.findByUserIgn(userIgn);
          if (found == null) {
            throw new CharacterNotFoundException(userIgn);
          }
          return found;
        },
        TaskContext.of("DB", "GetOrThrow", userIgn));
  }

  /**
   * 캐릭터 기본 정보 보강 (TieredCache L1/L2 + 15분 간격 갱신)
   *
   * <p>expectation-sequence-diagram 패턴 적용:
   *
   * <ul>
   *   <li>캐시 조회 (L1 → L2): TieredCache Single-flight 패턴
   *   <li>캐시 MISS 시 API 호출 → L2 → L1 저장
   *   <li>비동기 DB 저장: API 응답 후 Background로 DB 업데이트
   * </ul>
   *
   * <p>character_image가 수시로 바뀌므로 15분 간격으로 갱신
   *
   * @param character 기본 정보가 없거나 오래된 캐릭터 엔티티
   * @return 기본 정보가 보강된 캐릭터 (또는 원본 그대로)
   */
  public GameCharacter enrichCharacterBasicInfo(GameCharacter character) {
    // DB에 이미 있고 15분 미경과 시 그대로 반환 (DB 우선)
    if (!needsBasicInfoRefresh(character)) {
      return character;
    }

    TaskContext context =
        TaskContext.of("Character", "EnrichBasicInfo", character.getUserIgn().value());

    return executor.executeOrDefault(
        () -> fetchAndUpdateBasicInfo(character),
        character, // 실패 시 원본 반환
        context);
  }

  /**
   * TieredCache를 통한 기본 정보 조회 및 엔티티 업데이트
   *
   * <p>cache-sequence.md 패턴: L1 → L2 → API 호출 순서
   *
   * <p>Spring Cache의 {@code cache.get(key, Callable)}는 동기 API이므로 Callable 내부에서 CF 결과를 기다려야 합니다. CF
   * 체이닝 후 join()을 사용하는 것은 동기 경계에서 불가피합니다.
   */
  private GameCharacter fetchAndUpdateBasicInfo(GameCharacter character) {
    String ocid = character.getOcid();
    Cache cache = cacheManager.getCache("characterBasic");

    // TieredCache: L1 → L2 → API 호출 (Single-flight 패턴)
    CharacterBasicResponse basicInfo =
        cache.get(ocid, () -> loadCharacterBasicFromApi(character, ocid));

    // 엔티티 업데이트 (메모리)
    GameCharacter updated = updateCharacterWithBasicInfo(character, basicInfo);

    // 비동기 DB 저장 (Background) — 별도 빈(CharacterAsyncService)으로 @Async 활성화
    characterAsyncService.saveCharacterBasicInfoAsync(updated);

    return updated;
  }

  /** 캐시 MISS 시 API 호출로 기본 정보 로드 (lambda 추출) */
  private CharacterBasicResponse loadCharacterBasicFromApi(GameCharacter character, String ocid) {
    return executor.execute(
        () -> {
          log.info("🔄 [Enrich] 캐릭터 기본 정보 API 호출: {} (캐시 MISS)", character.getUserIgn().value());
          // Sync boundary: Spring Cache Callable은 동기 API이므로 join() 불가피
          return nexonApiClient
              .getCharacterBasic(ocid)
              .orTimeout(API_TIMEOUT_SECONDS, TimeUnit.SECONDS)
              .join();
        },
        TaskContext.of("Cache", "LoadCharacterBasic", ocid));
  }

  /** 엔티티에 기본 정보 설정 - Kotlin immutable data class용 with* 메서드 사용 */
  private GameCharacter updateCharacterWithBasicInfo(
      GameCharacter character, CharacterBasicResponse basicInfo) {
    String worldName = basicInfo.getWorldName();
    String characterClass = basicInfo.getCharacterClass();
    String characterImage = basicInfo.getCharacterImage();

    // Kotlin data class의 withBasicInfo 메서드 사용 (불변 패턴)
    return character.withBasicInfo(worldName, characterClass, characterImage);
  }

  /** 캐릭터 기본 정보 갱신 필요 여부 확인 (15분 경과 체크) */
  private boolean needsBasicInfoRefresh(GameCharacter character) {
    // worldName이 없으면 갱신 필요
    if (character.getWorldName() == null) {
      return true;
    }
    // 마지막 업데이트 시각이 없거나 15분 이상 경과했으면 갱신 필요
    LocalDateTime basicInfoUpdatedAt = character.getBasicInfoUpdatedAt();
    return basicInfoUpdatedAt == null
        || basicInfoUpdatedAt.isBefore(LocalDateTime.now().minusMinutes(15));
  }

  /**
   * 좋아요 버퍼 동기화용 Pessimistic Lock 조회
   *
   * <p>Note: 새 레포지토리 인터페이스에는 findByUserIgnWithPessimisticLock이 없으므로 findByUserIgn로 대체하고 트랜잭션에서 낙관적
   * 락(version) 사용
   */
  @Transactional("transactionManager")
  @ObservedTransaction("service.v2.GameCharacterService.getCharacterForUpdate")
  public GameCharacter getCharacterForUpdate(String userIgn) {
    return executor.execute(
        () -> {
          GameCharacter found = gameCharacterRepository.findByUserIgn(userIgn);
          if (found == null) {
            throw new CharacterNotFoundException(userIgn);
          }
          return found;
        },
        TaskContext.of("DB", "GetForUpdate", userIgn));
  }
}
