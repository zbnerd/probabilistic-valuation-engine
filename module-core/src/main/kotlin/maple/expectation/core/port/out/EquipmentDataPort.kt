package maple.expectation.core.port.out

import java.util.Optional
import maple.expectation.core.domain.model.CharacterId
import maple.expectation.core.domain.model.equipment.EquipmentData

/**
 * Port for retrieving equipment data.
 *
 * <p>Implemented by module-infra adapters (database, cache, external API).
 *
 * <p>This interface abstracts the data source for equipment data, allowing core business logic to
 * remain independent of infrastructure.
 */
interface EquipmentDataPort {

  /**
   * Find equipment data by character ID.
   *
   * @param characterId the unique character identifier
   * @return Optional containing the equipment data, or empty if not found
   */
  fun findByCharacterId(characterId: CharacterId): Optional<EquipmentData>

  /**
   * Find equipment data by character OCID.
   *
   * @param ocid the character's OCID
   * @return Optional containing the equipment data, or empty if not found
   */
  fun findByOcid(ocid: String): Optional<EquipmentData>

  /**
   * Save or update equipment data for a character.
   *
   * @param characterId the unique character identifier
   * @param equipmentData the equipment data to save
   */
  fun save(characterId: CharacterId, equipmentData: EquipmentData)

  /**
   * Delete equipment data for a character.
   *
   * @param characterId the unique character identifier
   */
  fun deleteByCharacterId(characterId: CharacterId)
}
