package maple.expectation.application.service;

import java.time.Duration;
import java.util.Optional;
import maple.expectation.core.domain.model.character.CharacterId;
import maple.expectation.core.domain.model.equipment.CharacterEquipment;
import maple.expectation.core.domain.model.equipment.EquipmentData;
import maple.expectation.infrastructure.persistence.repository.CharacterEquipmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application Service for CharacterEquipment domain operations.
 *
 * <p>This service orchestrates business operations and manages transaction boundaries. It acts as
 * the use-case layer in Clean Architecture, coordinating between the domain layer and
 * infrastructure.
 *
 * <h3>Responsibilities</h3>
 *
 * <ul>
 *   <li>Transaction management (@Transactional)
 *   <li>Use-case orchestration (find, save, update operations)
 *   <li>Domain logic coordination
 * </ul>
 *
 * <h3>Design Principles</h3>
 *
 * <ul>
 *   <li><b>Thin Layer</b>: Delegates business logic to domain entities
 *   <li><b>Stateless</b>: No instance state, only dependencies
 *   <li><b>Transaction Boundary</b>: All write operations are transactional
 * </ul>
 */
@Service
@Transactional("transactionManager")
public class EquipmentApplicationService {

  private final CharacterEquipmentRepository equipmentRepository;

  /**
   * ADR-084: Align TTL with EquipmentDbWorker.DB_TTL (15 minutes).
   *
   * <p>Previously used 24-hour TTL, which caused stale data to be served for too long. The active
   * cache flow treats DB data as fresh for 15 minutes.
   *
   * @see maple.expectation.service.v2.worker.EquipmentDbWorker#DB_TTL
   */
  private static final Duration FRESH_TTL = Duration.ofMinutes(15);

  /**
   * Creates a new EquipmentApplicationService.
   *
   * @param equipmentRepository the repository port (must not be null)
   */
  public EquipmentApplicationService(CharacterEquipmentRepository equipmentRepository) {
    this.equipmentRepository = equipmentRepository;
  }

  /**
   * Finds equipment by character ID.
   *
   * <p>This is a read-only operation that doesn't require a transaction.
   *
   * @param characterId the character identifier
   * @return Optional containing the equipment if found
   */
  @Transactional(transactionManager = "tm", readOnly = true)
  public Optional<CharacterEquipment> findEquipment(CharacterId characterId) {
    if (characterId == null) {
      throw new IllegalArgumentException("CharacterId cannot be null");
    }
    return Optional.ofNullable(equipmentRepository.findById(characterId));
  }

  /**
   * Finds fresh equipment (updated within TTL).
   *
   * <p>Returns equipment only if it was updated within 15 minutes (FRESH_TTL).
   *
   * <p>ADR-084: TTL aligned with EquipmentDbWorker.DB_TTL for consistency.
   *
   * @param characterId the character identifier
   * @return Optional containing fresh equipment if found
   */
  @Transactional(transactionManager = "tm", readOnly = true)
  public Optional<CharacterEquipment> findFreshEquipment(CharacterId characterId) {
    if (characterId == null) {
      throw new IllegalArgumentException("CharacterId cannot be null");
    }
    CharacterEquipment equipment = equipmentRepository.findById(characterId);
    return Optional.ofNullable(equipment).filter(e -> e.isFresh(FRESH_TTL));
  }

  /**
   * Saves or updates equipment data.
   *
   * <p>This creates a new equipment record or updates an existing one within a transaction.
   *
   * @param characterId the character identifier
   * @param jsonData the equipment JSON content
   * @return the saved equipment
   */
  public CharacterEquipment saveEquipment(CharacterId characterId, String jsonData) {
    if (characterId == null) {
      throw new IllegalArgumentException("CharacterId cannot be null");
    }
    if (jsonData == null || jsonData.isBlank()) {
      throw new IllegalArgumentException("JSON data cannot be null or blank");
    }

    EquipmentData equipmentData = EquipmentData.of(jsonData);

    // Check if equipment exists
    Optional<CharacterEquipment> existing =
        Optional.ofNullable(equipmentRepository.findById(characterId));

    // Update existing equipment or create new
    return existing
        .map(e -> equipmentRepository.save(e.withUpdatedData(equipmentData)))
        .orElseGet(
            () -> equipmentRepository.save(CharacterEquipment.create(characterId, equipmentData)));
  }

  /**
   * Updates equipment data.
   *
   * <p>Updates the equipment JSON content and timestamp.
   *
   * @param characterId the character identifier
   * @param jsonData the new equipment JSON content
   * @return the updated equipment
   * @throws IllegalArgumentException if equipment not found
   */
  public CharacterEquipment updateEquipment(CharacterId characterId, String jsonData) {
    if (characterId == null) {
      throw new IllegalArgumentException("CharacterId cannot be null");
    }
    if (jsonData == null || jsonData.isBlank()) {
      throw new IllegalArgumentException("JSON data cannot be null or blank");
    }

    CharacterEquipment equipment =
        Optional.ofNullable(equipmentRepository.findById(characterId))
            .orElseThrow(() -> new IllegalArgumentException("Equipment not found: " + characterId));

    EquipmentData newData = EquipmentData.of(jsonData);
    CharacterEquipment updated = equipment.withUpdatedData(newData);
    return equipmentRepository.save(updated);
  }

  /**
   * Deletes equipment by character ID.
   *
   * @param characterId the character identifier
   */
  public void deleteEquipment(CharacterId characterId) {
    if (characterId == null) {
      throw new IllegalArgumentException("CharacterId cannot be null");
    }
    equipmentRepository.deleteById(characterId);
  }

  /**
   * Checks if equipment exists.
   *
   * @param characterId the character identifier
   * @return true if equipment exists, false otherwise
   */
  @Transactional(transactionManager = "transactionManager", readOnly = true)
  public boolean equipmentExists(CharacterId characterId) {
    if (characterId == null) {
      throw new IllegalArgumentException("CharacterId cannot be null");
    }
    return equipmentRepository.existsById(characterId);
  }
}
