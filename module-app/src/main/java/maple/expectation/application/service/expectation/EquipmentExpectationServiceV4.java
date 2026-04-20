package maple.expectation.application.service.expectation;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.application.service.character.GameCharacterFacade;
import maple.expectation.application.service.character.GameCharacterService;
import maple.expectation.application.service.expectation.cache.ExpectationCacheCoordinator;
import maple.expectation.application.service.expectation.event.ViewTransformer;
import maple.expectation.application.service.expectation.persistence.ExpectationPersistenceService;
import maple.expectation.core.calculator.port.StarforceLookupPort;
import maple.expectation.core.domain.cost.CostFormatter;
import maple.expectation.core.domain.model.character.GameCharacter;
import maple.expectation.core.port.out.CacheWarmupPort;
import maple.expectation.error.exception.StarforceNotInitializedException;
import maple.expectation.infrastructure.aop.annotation.TraceLog;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.persistence.CharacterViewQueryServicePostgres;
import maple.expectation.infrastructure.provider.EquipmentDataProvider;
import maple.expectation.parser.EquipmentStreamingParser;
import maple.expectation.web.dto.CubeCalculationInput;
import maple.expectation.web.dto.v4.EquipmentExpectationResponseV4;
import maple.expectation.web.dto.v4.EquipmentExpectationResponseV4.CostBreakdownDto;
import maple.expectation.web.dto.v4.EquipmentExpectationResponseV4.PresetExpectation;
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
  private final Executor presetExecutor;
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
      @Qualifier("presetCalculationExecutor") Executor presetExecutor,
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
    this.presetExecutor = presetExecutor;
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
    return calculateExpectationAsync(userIgn, false);
  }

  /** 캐릭터 기대값 계산 (비동기, force 옵션) */
  @TraceLog
  public CompletableFuture<EquipmentExpectationResponseV4> calculateExpectationAsync(
      String userIgn, boolean force) {
    return calculateExpectationAsync(userIgn, force, null);
  }

  @TraceLog
  public CompletableFuture<EquipmentExpectationResponseV4> calculateExpectationAsync(
      String userIgn, boolean force, @Nullable String taskId) {
    return CompletableFuture.supplyAsync(
            () -> selfProvider.getObject().calculateExpectation(userIgn, force, taskId),
            equipmentExecutor)
        .orTimeout(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  /** GZIP 압축된 기대값 응답 반환 (비동기) (#262 성능 최적화) */
  @TraceLog
  public CompletableFuture<byte[]> getGzipExpectationAsync(String userIgn, boolean force) {
    return CompletableFuture.supplyAsync(
            () -> getGzipExpectation(userIgn, force), equipmentExecutor)
        .orTimeout(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  /** 캐릭터 기대값 계산 (동기, force 옵션) */
  @Transactional("transactionManager")
  public EquipmentExpectationResponseV4 calculateExpectation(String userIgn, boolean force) {
    return calculateExpectation(userIgn, force, null);
  }

  @Transactional("transactionManager")
  public EquipmentExpectationResponseV4 calculateExpectation(
      String userIgn, boolean force, @Nullable String taskId) {
    validateInitialized();
    EquipmentExpectationResponseV4 response =
        cacheCoordinator.getOrCalculate(
            userIgn, force, () -> doCalculateExpectation(userIgn, taskId));

    if (taskId != null && response.isFromCache()) {
      syncCachedResponseToViewTable(userIgn, response, taskId);
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
      String userIgn, boolean force, @Nullable String taskId) {
    validateInitialized();
    return doCalculateExpectationWriteOnly(userIgn);
  }

  /** GZIP 압축된 기대값 응답 반환 (동기) */
  public byte[] getGzipExpectation(String userIgn, boolean force) {
    validateInitialized();
    return cacheCoordinator.getGzipOrCalculate(
        userIgn, force, () -> doCalculateExpectation(userIgn, null));
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
   * <p>loadEquipmentDataAsync로 API 대기 시간 분리. TieredCache Callable 내에서는 .join() 사용 (분산 락 내부이므로 스레드
   * 점유 제한적)
   */
  private EquipmentExpectationResponseV4 doCalculateExpectation(
      String userIgn, @Nullable String taskId) {
    TaskContext context = TaskContext.of("ExpectationV4", "Calculate", userIgn);

    return executor.execute(
        () -> {
          GameCharacter character = findCharacterBypassingWorker(userIgn);
          byte[] equipmentData =
              loadEquipmentDataAsync(character).join(); // TieredCache Callable 내부 → 동기 필요
          List<PresetExpectation> presetResults =
              calculateAllPresets(equipmentData, character.getCharacterClass());
          PresetExpectation maxPreset = findMaxPreset(presetResults);
          persistenceService.saveResults(character.getId(), presetResults);
          EquipmentExpectationResponseV4 response =
              buildResponse(userIgn, maxPreset, presetResults, false);

          // V5: Inline View Write — same TX, no queue (Async Materialized View pattern)
          // TODO: Replace with async projection (event-driven) when scaling out
          syncToViewTable(userIgn, character, response, taskId);

          return response;
        },
        context);
  }

  /**
   * Write-only calculation — reuses existing private methods but skips persistence and view sync.
   *
   * <p>Used by BS2 Phase 1 batch UPSERT worker for pure calculation.
   */
  private EquipmentExpectationResponseV4 doCalculateExpectationWriteOnly(String userIgn) {
    TaskContext context = TaskContext.of("ExpectationV4", "CalculateWriteOnly", userIgn);

    return executor.execute(
        () -> {
          GameCharacter character = findCharacterBypassingWorker(userIgn);
          byte[] equipmentData = loadEquipmentDataAsync(character).join();
          List<PresetExpectation> presetResults =
              calculateAllPresets(equipmentData, character.getCharacterClass());
          PresetExpectation maxPreset = findMaxPreset(presetResults);
          return buildResponse(userIgn, maxPreset, presetResults, false);
        },
        context);
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
      @Nullable String taskId) {
    CharacterViewQueryServicePostgres viewService = viewQueryServiceProvider.getIfAvailable();
    if (viewService == null) return; // V5 미활성화 시 skip

    executor.executeVoidJava(
        () -> {
          var entity = viewTransformer.toEntityFromResponse(userIgn, character, response, taskId);
          viewService.upsert(entity);
          log.debug("[ExpectationV4] Synced to view table: userIgn={}", userIgn);
        },
        TaskContext.of("ExpectationV4", "SyncView", userIgn));
  }

  /**
   * V2 워커 풀을 우회하고 직접 캐릭터를 조회/생성 (V5 CQRS 전용)
   *
   * <p>V5 워커가 V2 워커 풀에 의존하지 않도록 V2 Service를 직접 호출
   */
  private void syncCachedResponseToViewTable(
      String userIgn, EquipmentExpectationResponseV4 response, String taskId) {
    GameCharacter character = findCharacterBypassingWorker(userIgn);
    syncToViewTable(userIgn, character, response, taskId);
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

  private EquipmentExpectationResponseV4 buildResponse(
      String userIgn,
      PresetExpectation maxPreset,
      List<PresetExpectation> presetResults,
      boolean fromCache) {
    double totalCost = maxPreset != null ? maxPreset.getTotalExpectedCost() : 0.0;
    CostBreakdownDto totalBreakdown =
        maxPreset != null ? maxPreset.getCostBreakdown() : CostBreakdownDto.empty();
    int maxPresetNo = maxPreset != null ? maxPreset.getPresetNo() : 0;

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

  private List<PresetExpectation> calculateAllPresets(byte[] equipmentData, String characterClass) {
    byte[] decompressedData = streamingParser.decompressIfNeeded(equipmentData);

    // 1-pass: parse all 3 presets from single JSON scan
    Map<Integer, List<CubeCalculationInput>> allPresetInputs =
        streamingParser.parseAllPresets(decompressedData);

    // Parallel preset + parallel equipment (thenCombine, no join inside)
    List<CompletableFuture<PresetExpectation>> futures =
        IntStream.rangeClosed(1, 3)
            .mapToObj(
                presetNo ->
                    presetHelper.calculatePresetAsync(
                        allPresetInputs.getOrDefault(presetNo, List.of()),
                        presetNo,
                        characterClass,
                        presetExecutor))
            .toList();

    return futures.stream()
        .map(this::joinPresetFuture)
        .filter(preset -> !preset.getItems().isEmpty())
        .toList();
  }

  private PresetExpectation joinPresetFuture(CompletableFuture<PresetExpectation> future) {
    return executor.execute(
        () -> future.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        TaskContext.of("V4", "PresetJoin"));
  }
}
