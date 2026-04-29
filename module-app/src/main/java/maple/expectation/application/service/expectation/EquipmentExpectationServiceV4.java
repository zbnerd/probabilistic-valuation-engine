package maple.expectation.application.service.expectation;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.application.service.character.GameCharacterFacade;
import maple.expectation.application.service.character.GameCharacterService;
import maple.expectation.application.service.expectation.cache.ExpectationCacheCoordinator;
import maple.expectation.application.service.expectation.event.ViewTransformer;
import maple.expectation.application.service.expectation.persistence.ExpectationPersistenceService;
import maple.expectation.core.calculator.port.StarforceLookupPort;
import maple.expectation.core.domain.cost.CostFormatter;
import maple.expectation.core.domain.model.character.GameCharacter;
import maple.expectation.core.dto.cube.CubeCalculationInput;
import maple.expectation.core.dto.v4.EquipmentExpectationResponseV4;
import maple.expectation.core.dto.v4.EquipmentExpectationResponseV4.CostBreakdownDto;
import maple.expectation.core.dto.v4.EquipmentExpectationResponseV4.PresetExpectation;
import maple.expectation.core.port.out.CacheWarmupPort;
import maple.expectation.error.exception.StarforceNotInitializedException;
import maple.expectation.infrastructure.aop.annotation.TraceLog;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.persistence.CharacterViewQueryServicePostgres;
import maple.expectation.infrastructure.provider.EquipmentDataProvider;
import maple.expectation.parser.EquipmentStreamingParser;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * V4 장비 기대값 서비스 - Facade (P1-5: God Class 분해)
 *
 * <h3>책임: 비동기 오케스트레이션</h3>
 *
 * <ul>
 *   <li>비동기 dispatch (calculateExpectationAsync, getGzipExpectationAsync)
 *   <li>캐릭터 조회 → 장비 로드 → 프리셋 계산 → 응답 빌드
 * </ul>
 *
 * <h3>위임된 책임</h3>
 *
 * <ul>
 *   <li>프리셋 계산: {@link maple.expectation.application.service.expectation.PresetCalculationHelper}
 *   <li>캐시 관리: {@link ExpectationCacheCoordinator}
 *   <li>영속성: {@link ExpectationPersistenceService}
 * </ul>
 */
@Slf4j
@Service
public class EquipmentExpectationServiceV4 implements CacheWarmupPort {

  private static final long ASYNC_TIMEOUT_SECONDS = 30L;

  private final GameCharacterFacade gameCharacterFacade;
  private final GameCharacterService gameCharacterService;
  private final EquipmentDataProvider equipmentProvider;
  private final EquipmentStreamingParser streamingParser;
  private final maple.expectation.application.service.expectation.PresetCalculationHelper
      presetHelper;
  private final StarforceLookupPort starforceLookupPort;
  private final LogicExecutor executor;
  private final Executor equipmentExecutor;
  private final ExpectationCacheCoordinator cacheCoordinator;
  private final ExpectationPersistenceService persistenceService;
  private final ObjectProvider<EquipmentExpectationServiceV4> selfProvider;
  private final maple.expectation.infrastructure.config.NexonApiProperties nexonApiProperties;
  private final ViewTransformer viewTransformer;
  private final ObjectProvider<CharacterViewQueryServicePostgres> viewQueryServiceProvider;

  public EquipmentExpectationServiceV4(
      GameCharacterFacade gameCharacterFacade,
      GameCharacterService gameCharacterService,
      EquipmentDataProvider equipmentProvider,
      EquipmentStreamingParser streamingParser,
      maple.expectation.application.service.expectation.PresetCalculationHelper presetHelper,
      StarforceLookupPort starforceLookupPort,
      LogicExecutor executor,
      @Qualifier("equipmentProcessingExecutor") Executor equipmentExecutor,
      ExpectationCacheCoordinator cacheCoordinator,
      ExpectationPersistenceService persistenceService,
      ObjectProvider<EquipmentExpectationServiceV4> selfProvider,
      maple.expectation.infrastructure.config.NexonApiProperties nexonApiProperties,
      ViewTransformer viewTransformer,
      ObjectProvider<CharacterViewQueryServicePostgres> viewQueryServiceProvider) {
    this.gameCharacterFacade = gameCharacterFacade;
    this.gameCharacterService = gameCharacterService;
    this.equipmentProvider = equipmentProvider;
    this.streamingParser = streamingParser;
    this.presetHelper = presetHelper;
    this.starforceLookupPort = starforceLookupPort;
    this.executor = executor;
    this.equipmentExecutor = equipmentExecutor;
    this.cacheCoordinator = cacheCoordinator;
    this.persistenceService = persistenceService;
    this.selfProvider = selfProvider;
    this.nexonApiProperties = nexonApiProperties;
    this.viewTransformer = viewTransformer;
    this.viewQueryServiceProvider = viewQueryServiceProvider;
  }

  // ==================== Public API ====================

  /** 캐릭터 기대값 계산 (비동기) */
  @TraceLog
  public CompletableFuture<EquipmentExpectationResponseV4> calculateExpectationAsync(
      String userIgn) {
    return calculateExpectationAsync(userIgn, false, null, 1);
  }

  /** 캐릭터 기대값 계산 (비동기, force 옵션) */
  @TraceLog
  public CompletableFuture<EquipmentExpectationResponseV4> calculateExpectationAsync(
      String userIgn, boolean force) {
    return calculateExpectationAsync(userIgn, force, null, 1);
  }

  @TraceLog
  public CompletableFuture<EquipmentExpectationResponseV4> calculateExpectationAsync(
      String userIgn, boolean force, @Nullable String taskId, int presetNo) {
    return CompletableFuture.supplyAsync(
            () -> selfProvider.getObject().calculateExpectation(userIgn, force, taskId, presetNo),
            equipmentExecutor)
        .orTimeout(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  /** GZIP 압축된 기대값 응답 반환 (비동기) (#262 성능 최적화) */
  @TraceLog
  public CompletableFuture<byte[]> getGzipExpectationAsync(
      String userIgn, boolean force, int presetNo) {
    return CompletableFuture.supplyAsync(
            () -> getGzipExpectation(userIgn, force, presetNo), equipmentExecutor)
        .orTimeout(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  /** 캐릭터 기대값 계산 (동기, force 옵션) */
  @Transactional("transactionManager")
  public EquipmentExpectationResponseV4 calculateExpectation(String userIgn, boolean force) {
    return calculateExpectation(userIgn, force, null, 1);
  }

  @Transactional("transactionManager")
  public EquipmentExpectationResponseV4 calculateExpectation(
      String userIgn, boolean force, @Nullable String taskId, int presetNo) {
    validateInitialized();
    EquipmentExpectationResponseV4 response =
        cacheCoordinator.getOrCalculate(
            userIgn, force, () -> doCalculateExpectation(userIgn, taskId, presetNo), presetNo);

    if (taskId != null && response.isFromCache()) {
      syncCachedResponseToViewTable(userIgn, response, taskId, presetNo);
    }

    return response;
  }

  /** 캐시 웜업 (CacheWarmupPort 구현) */
  @Override
  public void warmup(String userIgn, boolean force) {
    calculateExpectation(userIgn, force);
  }

  /** Write-only calculation — no persistence, cache, or view writes (BS2 Phase 1) */
  public EquipmentExpectationResponseV4 calculateExpectationWriteOnly(
      String userIgn, boolean force, @Nullable String taskId, int presetNo) {
    validateInitialized();
    return doCalculateExpectationWriteOnly(userIgn, presetNo);
  }

  /** GZIP 압축된 기대값 응답 반환 (동기) */
  public byte[] getGzipExpectation(String userIgn, boolean force, int presetNo) {
    validateInitialized();
    return cacheCoordinator.getGzipOrCalculate(
        userIgn, force, () -> doCalculateExpectation(userIgn, null, presetNo), presetNo);
  }

  /** L1 캐시 직접 조회 - Fast Path (#264 성능 최적화) */
  @Nullable public byte[] getGzipFromL1CacheDirect(String userIgn) {
    return cacheCoordinator.getGzipFromL1CacheDirect(userIgn);
  }

  // ==================== Internal Calculation ====================

  private void validateInitialized() {
    if (!starforceLookupPort.isInitialized()) {
      throw new StarforceNotInitializedException();
    }
  }

  /**
   * 실제 기대값 계산 로직 (Singleflight Leader가 실행)
   *
   * <h3>P0-2: 비동기 체이닝 적용</h3>
   *
   * <p>이 메서드는 TieredCache의 Callable 내부에서 호출되므로 동기 경계(boundary)입니다. loadEquipmentDataAsync의 CF 결과를
   * 가져오기 위해 CF 체이닝으로 연결합니다. 캐시 히트 시 CF는 이미 완료된 상태이며, 미스 시에만 실제 비동기 로드가 발생합니다.
   */
  private EquipmentExpectationResponseV4 doCalculateExpectation(
      String userIgn, @Nullable String taskId, int presetNo) {
    TaskContext context = TaskContext.of("ExpectationV4", "Calculate", userIgn);

    return executor.execute(
        () -> buildExpectationFromEquipmentData(userIgn, taskId, presetNo), context);
  }

  /** 장비 데이터 로드 → 프리셋 계산 → 응답 빌드 (lambda 추출) */
  private EquipmentExpectationResponseV4 buildExpectationFromEquipmentData(
      String userIgn, @Nullable String taskId, int presetNo) {

    GameCharacter character = findCharacterBypassingWorker(userIgn);
    byte[] equipmentData = loadEquipmentDataSync(character);
    return buildPresetResponseAndPersist(userIgn, character, equipmentData, taskId, presetNo);
  }

  /**
   * 장비 데이터를 동기적으로 로드.
   *
   * <p>캐시 Callable의 동기 경계에서 호출됩니다. loadEquipmentDataAsync가 반환하는 CF는 캐시 히트 시 이미 완료(completedFuture)
   * 상태이며, 미스 시에만 실제 API 비동기 호출이 수행됩니다. 후자의 경우 equipmentExecutor 스레드에서 이미 실행 중이므로 블로킹은 동일한 스레드 풀 내에서
   * 발생합니다.
   */
  private byte[] loadEquipmentDataSync(GameCharacter character) {
    return loadEquipmentDataAsync(character).join();
  }

  /** 프리셋 계산 및 응답 빌드 (lambda 추출) */
  private EquipmentExpectationResponseV4 buildPresetResponseAndPersist(
      String userIgn,
      GameCharacter character,
      byte[] equipmentData,
      @Nullable String taskId,
      int presetNo) {

    List<PresetExpectation> presetResults =
        calculatePresets(equipmentData, character.getCharacterClass(), presetNo);
    PresetExpectation maxPreset = findMaxPreset(presetResults);
    if (maxPreset == null) {
      return buildEmptyResponse(userIgn);
    }
    persistenceService.saveResults(character.getId(), presetResults);
    EquipmentExpectationResponseV4 response =
        buildResponse(userIgn, maxPreset, presetResults, false, presetNo);

    // V5: Inline View Write — same TX, no queue (Async Materialized View pattern)
    syncToViewTable(userIgn, character, response, taskId, presetNo);
    return response;
  }

  /**
   * Write-only calculation — reuses existing private methods but skips persistence and view sync.
   *
   * <p>Used by BS2 Phase 1 batch UPSERT worker for pure calculation.
   */
  private EquipmentExpectationResponseV4 doCalculateExpectationWriteOnly(
      String userIgn, int presetNo) {
    TaskContext context = TaskContext.of("ExpectationV4", "CalculateWriteOnly", userIgn);

    return executor.execute(() -> buildWriteOnlyExpectation(userIgn, presetNo), context);
  }

  /** Write-only 계산 로직 (lambda 추출) */
  private EquipmentExpectationResponseV4 buildWriteOnlyExpectation(String userIgn, int presetNo) {
    GameCharacter character = findCharacterBypassingWorker(userIgn);
    byte[] equipmentData = loadEquipmentDataSync(character);
    List<PresetExpectation> presetResults =
        calculatePresets(equipmentData, character.getCharacterClass(), presetNo);
    PresetExpectation maxPreset = findMaxPreset(presetResults);
    if (maxPreset == null) {
      return buildEmptyResponse(userIgn);
    }
    return buildResponse(userIgn, maxPreset, presetResults, false, presetNo);
  }

  /**
   * V5 CQRS: Inline view table write (best-effort).
   *
   * <p>Writes precomputed result to character_valuation_views within the same transaction. Skipped
   * when V5 is not enabled (ObjectProvider → null).
   *
   * <p>Async Materialized View pattern: Worker → DB write → API read.
   */
  private void syncToViewTable(
      String userIgn,
      GameCharacter character,
      EquipmentExpectationResponseV4 response,
      @Nullable String taskId,
      int presetNo) {
    CharacterViewQueryServicePostgres viewService = viewQueryServiceProvider.getIfAvailable();
    if (viewService == null) return; // V5 미활성화 시 skip

    executor.executeVoidJava(
        () -> {
          var entity =
              viewTransformer.toEntityFromResponse(userIgn, character, response, taskId, presetNo);
          viewService.upsert(entity);
          log.debug(
              "[ExpectationV4] Synced to view table: userIgn={}, presetNo={}", userIgn, presetNo);
        },
        TaskContext.of("ExpectationV4", "SyncView", userIgn));
  }

  /**
   * V2 워커 풀을 우회하고 직접 캐릭터를 조회/생성 (V5 CQRS 전용)
   *
   * <p>V5 워커가 V2 워커 풀에 의존하지 않도록 V2 Service를 직접 호출
   */
  private void syncCachedResponseToViewTable(
      String userIgn, EquipmentExpectationResponseV4 response, String taskId, int presetNo) {
    GameCharacter character = findCharacterBypassingWorker(userIgn);
    syncToViewTable(userIgn, character, response, taskId, presetNo);
  }

  private GameCharacter findCharacterBypassingWorker(String userIgn) {
    return executor.execute(
        () -> {
          // 1. 캐시된 캐릭터 조회
          Optional<GameCharacter> cached = gameCharacterService.getCharacterIfExist(userIgn);
          if (cached.isPresent()) {
            return cached.get();
          }

          // 2. Negative Cache 확인
          if (gameCharacterService.isNonExistent(userIgn)) {
            throw new maple.expectation.error.exception.CharacterNotFoundException(userIgn);
          }

          // 3. 직접 생성 (V2 워커 풀 우회)
          return gameCharacterService.createNewCharacter(userIgn);
        },
        TaskContext.of("V4", "FindCharacterBypassingWorker", userIgn));
  }

  private PresetExpectation findMaxPreset(List<PresetExpectation> presetResults) {
    return presetResults.stream()
        .max((p1, p2) -> Double.compare(p1.getTotalExpectedCost(), p2.getTotalExpectedCost()))
        .orElse(null);
  }

  private EquipmentExpectationResponseV4 buildEmptyResponse(String userIgn) {
    return EquipmentExpectationResponseV4.builder()
        .userIgn(userIgn)
        .calculatedAt(LocalDateTime.now())
        .fromCache(false)
        .totalExpectedCost(0.0)
        .totalCostText(CostFormatter.format(0.0))
        .totalCostBreakdown(CostBreakdownDto.empty())
        .maxPresetNo(0)
        .presets(List.of())
        .build();
  }

  private EquipmentExpectationResponseV4 buildResponse(
      String userIgn,
      PresetExpectation maxPreset,
      List<PresetExpectation> presetResults,
      boolean fromCache,
      int presetNo) {
    double totalCost = maxPreset != null ? maxPreset.getTotalExpectedCost() : 0.0;
    CostBreakdownDto totalBreakdown =
        maxPreset != null ? maxPreset.getCostBreakdown() : CostBreakdownDto.empty();
    int maxPresetNo = presetNo;

    return EquipmentExpectationResponseV4.builder()
        .userIgn(userIgn)
        .calculatedAt(LocalDateTime.now())
        .fromCache(fromCache)
        .totalExpectedCost(totalCost)
        .totalCostText(CostFormatter.format(totalCost))
        .totalCostBreakdown(totalBreakdown)
        .maxPresetNo(maxPresetNo)
        .presets(presetResults)
        .build();
  }

  // ==================== Equipment Loading ====================

  /**
   * 장비 데이터 비동기 로드 (P0-2: .join() 블로킹 분리)
   *
   * <p>DB에 캐시된 데이터가 있으면 즉시 반환, 없으면 API 비동기 호출
   *
   * <p>🔥 FAN-OUT 적용: Nexon API 3개 병렬 호출 (getCharacterBasic, getItemData, getCubeHistory)
   */
  private CompletableFuture<byte[]> loadEquipmentDataAsync(GameCharacter character) {
    if (character.getEquipment() != null && character.getEquipment().jsonContent() != null) {
      return CompletableFuture.completedFuture(character.getEquipment().jsonContent().getBytes());
    }
    return equipmentProvider
        .getRawEquipmentDataWithFanout(character.getCharacterId().value()) // 🔥 fan-out 메서드 호출
        .orTimeout(nexonApiProperties.getDataLoadTimeoutSeconds(), TimeUnit.SECONDS);
  }

  // ==================== Preset Calculation ====================

  private List<PresetExpectation> calculatePresets(
      byte[] equipmentData, String characterClass, int presetNo) {
    byte[] decompressedData = streamingParser.decompressIfNeeded(equipmentData);
    List<CubeCalculationInput> inputs =
        streamingParser.parseSinglePreset(decompressedData, presetNo);
    if (inputs.isEmpty()) return List.of();
    PresetExpectation result =
        joinPresetFuture(presetHelper.calculatePresetAsync(inputs, presetNo, characterClass));
    if (result.getItems().isEmpty()) return List.of();
    return List.of(result);
  }

  private PresetExpectation joinPresetFuture(CompletableFuture<PresetExpectation> future) {
    return executor.execute(
        () -> future.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        TaskContext.of("V4", "PresetJoin"));
  }
}
