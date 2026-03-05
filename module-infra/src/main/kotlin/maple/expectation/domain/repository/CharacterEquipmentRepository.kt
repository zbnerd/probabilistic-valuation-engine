package maple.expectation.domain.repository

import maple.expectation.core.domain.model.character.CharacterId
import maple.expectation.core.domain.model.equipment.CharacterEquipment

/**
 * Repository Port for CharacterEquipment domain entity.
 *
 * <p>This is a <b>Port</b> interface in the Hexagonal Architecture pattern. It defines the contract
 * for persisting and retrieving {@link CharacterEquipment} domain entities without exposing any
 * infrastructure details.
 *
 * <h3>Design Principles</h3>
 *
 * <ul>
 *   <li><b>Interface Segregation</b>: Only domain-related methods are exposed
 *   <li><b>Dependency Inversion</b>: Domain layer depends on this interface, not implementation
 *   <li><b>Infrastructure Agnostic</b>: No JPA, SQL, or database-specific types
 *   <li><b>Domain-First</b>: Uses domain types ({@link CharacterId}, {@link CharacterEquipment})
 * </ul>
 *
 * <h3>Usage</h3>
 *
 * <pre>{@code
 * // In Application Service or Domain Service
 * class EquipmentApplicationService(
 *     private val repository: CharacterEquipmentRepository
 * ) {
 *     fun getEquipment(id: CharacterId): CharacterEquipment {
 *         return repository.findById(id)
 *             ?: throw EquipmentNotFoundException(id)
 *     }
 * }
 * }</pre>
 *
 * @see CharacterEquipment
 * @see CharacterId
 */
interface CharacterEquipmentRepository {

    /**
     * Finds equipment by character ID.
     *
     * @param characterId the character identifier (must not be null)
     * @return CharacterEquipment if found, null otherwise
     * @throws IllegalArgumentException if characterId is null
     */
    fun findById(characterId: CharacterId): CharacterEquipment?

    /**
     * Saves equipment (insert or update).
     *
     * <p>This method handles both new entities and updates to existing entities.
     *
     * @param equipment the equipment to save (must not be null)
     * @return the saved equipment (possibly with generated fields)
     * @throws IllegalArgumentException if equipment is null
     * @deprecated Use {@code saveAll(List)} for batch operations (33x faster)
     */
    @Deprecated("Use saveAll for batch operations (33x faster)", ReplaceWith("saveAll(listOf(equipment))"))
    fun save(equipment: CharacterEquipment): CharacterEquipment

    /**
     * Batch saves equipment list (insert or update).
     *
     * <p>Uses JDBC batch operations with {@code ON DUPLICATE KEY UPDATE} for optimal performance.
     * This is 33x faster than individual {@code save()} calls.
     *
     * @param equipments list of equipments to save (must not be null)
     * @return list of saved equipments
     * @throws IllegalArgumentException if equipments is null
     */
    fun saveAll(equipments: List<CharacterEquipment>): List<CharacterEquipment>

    /**
     * Deletes equipment by character ID.
     *
     * @param characterId the character identifier (must not be null)
     * @throws IllegalArgumentException if characterId is null
     */
    fun deleteById(characterId: CharacterId)

    /**
     * Checks if equipment exists for the given character ID.
     *
     * @param characterId the character identifier (must not be null)
     * @return true if equipment exists, false otherwise
     * @throws IllegalArgumentException if characterId is null
     */
    fun existsById(characterId: CharacterId): Boolean
}
