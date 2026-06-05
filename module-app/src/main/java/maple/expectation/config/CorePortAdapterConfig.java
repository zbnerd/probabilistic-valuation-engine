package maple.expectation.config;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.application.service.calculator.PotentialCalculator;
import maple.expectation.core.domain.model.AlertMessage;
import maple.expectation.core.domain.model.AlertPriority;
import maple.expectation.core.domain.model.character.CharacterId;
import maple.expectation.core.domain.model.CubeRate;
import maple.expectation.core.domain.model.PotentialStat;
import maple.expectation.core.domain.model.equipment.CharacterEquipment;
import maple.expectation.core.domain.model.equipment.EquipmentData;
import maple.expectation.core.port.out.AlertPort;
import maple.expectation.core.port.out.CubeRatePort;
import maple.expectation.core.port.out.EquipmentDataPort;
import maple.expectation.core.port.out.ItemPricePort;
import maple.expectation.core.port.out.PotentialStatPort;
import maple.expectation.core.port.out.ShutdownDataPersistencePort;
import maple.expectation.infrastructure.persistence.repository.CharacterEquipmentRepository;
import maple.expectation.infrastructure.persistence.repository.CubeProbabilityRepository;
import maple.expectation.infrastructure.persistence.entity.CubeProbability;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Core Port Adapters Configuration
 *
 * <p>Provides Spring Bean definitions for core domain port adapters and calculators.
 *
 * <h3>Purpose</h3>
 *
 * <p>Bridges core domain port interfaces to repository implementations following hexagonal
 * architecture. This allows core domain to depend on ports while implementations are provided by
 * module-infra.
 *
 * <h3>Adapter Mappings</h3>
 *
 * <ul>
 *   <li>{@link CubeRatePort} → {@link CubeProbabilityRepository} (via adapter)
 *   <li>{@link EquipmentDataPort} → {@link CharacterEquipmentRepository} (via adapter)
 *   <li>{@link PotentialStatPort} → Mock implementation (TODO: implement actual data source)
 *   <li>{@link AlertPort} → Mock implementation (TODO: integrate with Discord/email)
 *   <li>{@link ItemPricePort} → Mock implementation (TODO: integrate with Nexon API)
 * </ul>
 *
 * @see maple.expectation.core.port.out
 * @see maple.expectation.domain.repository
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class CorePortAdapterConfig {

  private final CubeProbabilityRepository cubeProbabilityRepository;
  private final CharacterEquipmentRepository characterEquipmentRepository;

  /**
  public PotentialCalculator potentialCalculator(
      maple.expectation.core.domain.stat.StatParser statParser,
      maple.expectation.infrastructure.executor.LogicExecutor logicExecutor) {
    log.info("[CorePortAdapter] Initializing PotentialCalculator bean");
    return new PotentialCalculator(statParser, logicExecutor);
  }

  /**
   * CubeRate Port Adapter
   *
   * <p>Maps legacy {@link CubeProbability} entities to core {@link CubeRate} domain models.
   *
   * <p><b>Phase 3:</b> Replace with {@code
   * maple.expectation.infrastructure.adapter.CubeRateRepositoryAdapter}
   */
  @Bean
  public CubeRatePort cubeRatePort() {
    log.info("[CorePortAdapter] Initializing CubeRatePort -> CubeProbabilityRepository bridge");

    return new CubeRatePort() {
      @Override
      public List<CubeRate> findByCubeType(maple.expectation.core.domain.model.CubeType type) {
        return cubeProbabilityRepository.findAll().stream()
            .filter(p -> p.getCubeType().name().equals(type.name()))
            .map(
                p ->
                    new CubeRate(
                        p.getCubeType(),
                        p.getOptionName(),
                        p.getRate(),
                        p.getSlot(),
                        p.getGrade(),
                        p.getLevel(),
                        p.getPart()))
            .toList();
      }

      @Override
      public List<CubeRate> findAll() {
        return cubeProbabilityRepository.findAll().stream()
            .map(
                p ->
                    new CubeRate(
                        p.getCubeType(),
                        p.getOptionName(),
                        p.getRate(),
                        p.getSlot(),
                        p.getGrade(),
                        p.getLevel(),
                        p.getPart()))
            .toList();
      }
    };
  }

  /**
   * EquipmentData Port Adapter
   *
   * <p>Maps between core {@link EquipmentData} and legacy {@link CharacterEquipment} domain models.
   *
   * <p><b>Phase 3:</b> Replace with {@code
   * maple.expectation.infrastructure.adapter.EquipmentDataRepositoryAdapter}
   */
  @Bean
  public EquipmentDataPort equipmentDataPort() {
    log.info(
        "[CorePortAdapter] Initializing EquipmentDataPort -> CharacterEquipmentRepository bridge");

    return new EquipmentDataPort() {
      @Override
      public Optional<EquipmentData> findByCharacterId(CharacterId characterId) {
        return Optional.ofNullable(
                characterEquipmentRepository.findById(mapToLegacyCharacterId(characterId)))
            .map(CharacterEquipment::equipmentData);
      }

      @Override
      public Optional<EquipmentData> findByOcid(String ocid) {
        return Optional.ofNullable(
                characterEquipmentRepository.findById(
                    maple.expectation.core.domain.model.character.CharacterId.of(ocid)))
            .map(CharacterEquipment::equipmentData);
      }

      @Override
      public void save(CharacterId characterId, EquipmentData equipmentData) {
        CharacterEquipment legacy =
            CharacterEquipment.restore(
                mapToLegacyCharacterId(characterId), equipmentData, java.time.LocalDateTime.now());
        characterEquipmentRepository.save(legacy);
      }

      @Override
      public void deleteByCharacterId(CharacterId characterId) {
        characterEquipmentRepository.deleteById(mapToLegacyCharacterId(characterId));
      }
    };
  }

  /**
   * PotentialStat Port Adapter (Mock)
   *
   * <p>⚠️ <b>TODO:</b> Implement actual data source for potential stats.
   *
   * <p><b>Phase 3:</b> Replace with {@code
   * maple.expectation.infrastructure.adapter.PotentialStatRepositoryAdapter}
   */
  @Bean
  public PotentialStatPort potentialStatPort() {
    log.warn("[CorePortAdapter] PotentialStatPort using MOCK implementation");

    return new PotentialStatPort() {
      @Override
      public Optional<PotentialStat> findByOptionName(String optionName) {
        // TODO: Implement actual lookup from data source
        return Optional.empty();
      }

      @Override
      public boolean isValidOption(String optionName) {
        // TODO: Implement actual validation
        return false;
      }
    };
  }

  /**
   * Alert Port Adapter (Mock)
   *
   * <p>⚠️ <b>TODO:</b> Integrate with actual alerting system (Discord, email, etc.).
   *
   * <p><b>Phase 3:</b> Replace with {@code
   * maple.expectation.infrastructure.adapter.AlertNotificationAdapter}
   */
  @Bean
  public AlertPort alertPort() {
    log.warn("[CorePortAdapter] AlertPort using MOCK implementation");

    return new AlertPort() {
      @Override
      public boolean sendAlert(AlertMessage message) {
        log.info("[Mock Alert] {}", message);
        return true;
      }

      @Override
      public boolean sendAlert(AlertMessage message, AlertPriority priority) {
        log.info("[Mock Alert] [{}] {}", priority, message);
        return true;
      }
    };
  }

  /**
   * ItemPrice Port Adapter (Mock)
   *
   * <p>⚠️ <b>TODO:</b> Integrate with Nexon API for item price data.
   *
   * <p><b>Phase 3:</b> Replace with {@code
   * maple.expectation.infrastructure.adapter.NexonItemPriceAdapter}
   */
  @Bean
  public ItemPricePort itemPricePort() {
    log.warn("[CorePortAdapter] ItemPricePort using MOCK implementation");

    return new ItemPricePort() {
      @Override
      public Optional<maple.expectation.core.domain.model.ItemPrice> findByItemId(long itemId) {
        // TODO: Implement actual Nexon API integration
        return Optional.empty();
      }

      @Override
      public Optional<maple.expectation.core.domain.model.ItemPrice> findByItemName(
          String itemName) {
        // TODO: Implement actual Nexon API integration
        return Optional.empty();
      }
    };
  }

  /**
   * ShutdownDataPersistence Port Adapter
   *
   * <p>Provides file backup service for outbox entries during DLQ handling.
   *
   * <p>Implements {@link ShutdownDataPersistencePort} for use by DLQ handlers.
   */
  @Bean
  public ShutdownDataPersistencePort shutdownDataPersistencePort() {
    log.info("[CorePortAdapter] Initializing ShutdownDataPersistencePort bean");

    return new ShutdownDataPersistencePort() {
      @Override
      public void appendOutboxEntry(String requestId, String payload) {
        log.info(
            "[ShutdownDataPersistence] Backup entry - requestId: {}, payload length: {}",
            requestId,
            payload != null ? payload.length() : 0);
        // File backup implementation can be added here if needed
      }
    };
  }

  // ========== Mapping Helper Methods ==========

  private static maple.expectation.core.domain.model.character.CharacterId mapToLegacyCharacterId(
      CharacterId coreId) {
    return maple.expectation.core.domain.model.character.CharacterId.of(coreId.value());
  }
}
