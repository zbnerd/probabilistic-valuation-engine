package maple.expectation.domain.repository

import maple.expectation.core.domain.model.Page
import maple.expectation.core.domain.model.PageRequest
import maple.expectation.core.domain.model.character.GameCharacter

/**
 * GameCharacter Repository Interface (Port)
 *
 * <p><b>Purpose:</b> Defines the contract for character data persistence operations following the
 * Ports and Adapters pattern. This interface belongs to the domain layer and contains no
 * infrastructure dependencies.
 *
 * <p><b>Contract:</b>
 *
 * <ul>
 *   <li>All methods return domain entities, not implementation details
 *   <li>Nullable types are used for single-result queries that may return nothing
 *   <li>Empty collections are returned for list queries (never null)
 *   <li>Exceptions are unchecked and domain-specific
 * </ul>
 *
 * <p><b>Usage Example:</b>
 *
 * <pre>{@code
 * // Find character by OCID
 * val character = gameCharacterRepository.findByOcid("abc123")
 *
 * // Save new character
 * val newCharacter = GameCharacter("Player1", "xyz789")
 * val saved = gameCharacterRepository.save(newCharacter)
 *
 * // Find all active characters
 * val all = gameCharacterRepository.findAll()
 * }</pre>
 *
 * <p><b>Implementation Notes:</b>
 *
 * <ul>
 *   <li>Implementations must handle transaction management
 *   <li>Implementations should handle optimistic locking conflicts
 *   <li>Implementations must ensure thread-safety for concurrent operations
 * </ul>
 *
 * @see GameCharacter
 */
interface GameCharacterRepository {

    /**
     * Find a character by their OCID (Origin Character ID)
     *
     * <p>OCID is a unique identifier assigned by Nexon's API and does not change over time.
     *
     * @param ocid the character's OCID (must not be null)
     * @return GameCharacter if found, null otherwise
     * @throws IllegalArgumentException if ocid is null or blank
     */
    fun findByOcid(ocid: String): GameCharacter?

    /**
     * Find a character by their in-game name (IGN)
     *
     * <p>IGN can change over time, so this method returns the first match. Use with caution.
     *
     * @param userIgn the character's in-game name (must not be null)
     * @return GameCharacter if found, null otherwise
     * @throws IllegalArgumentException if userIgn is null or blank
     */
    fun findByUserIgn(userIgn: String): GameCharacter?

    /**
     * Retrieve all characters from the database
     *
     * <p><b>Warning:</b> This method may return a large dataset. Use pagination or filtering for
     * production queries.
     *
     * @return List of all characters (empty list if none exist, never null)
     */
    fun findAll(): List<GameCharacter>

    /**
     * Retrieve characters with pagination
     *
     * <p>This method is preferred over {@code findAll()} for large datasets to avoid memory issues.
     *
     * @param pageRequest pagination parameters (page, size)
     * @return Page of characters matching the pagination criteria
     */
    fun findAll(pageRequest: PageRequest): Page<GameCharacter>

    /**
     * Find all active characters (updated within the last 30 days)
     *
     * <p>Active characters are defined by {@code GameCharacter.isActive()} - characters updated
     * within 30 days are considered active.
     *
     * @return List of active characters (empty list if none exist, never null)
     */
    fun findActiveCharacters(): List<GameCharacter>

    /**
     * Save a character (create or update)
     *
     * <p>If the character has a null ID, a new record is created. Otherwise, the existing record is
     * updated. This operation must handle optimistic locking via the version field.
     *
     * @param character the character to save (must not be null)
     * @return the saved character with generated ID and updated timestamps
     * @throws IllegalArgumentException if character is null
     * @throws javax.persistence.OptimisticLockException if version conflict occurs
     */
    fun save(character: GameCharacter): GameCharacter

    /**
     * Delete a character by OCID
     *
     * <p><b>Warning:</b> This is a destructive operation. Consider soft deletion for production use.
     *
     * @param ocid the OCID of the character to delete (must not be null)
     * @throws IllegalArgumentException if ocid is null or blank
     */
    fun deleteByOcid(ocid: String)

    /**
     * Check if a character exists by OCID
     *
     * <p>This method is more efficient than {@code findByOcid(String)}
     * when you only need to check existence without loading the entity.
     *
     * @param ocid the OCID to check (must not be null)
     * @return true if a character with the given OCID exists, false otherwise
     * @throws IllegalArgumentException if ocid is null or blank
     */
    fun existsByOcid(ocid: String): Boolean

    /**
     * Increment the like count for a character by user IGN
     *
     * <p>This method is used for batch updates in the like synchronization process.
     * It directly updates the like_count in the database without loading the entity.
     *
     * @param userIgn the in-game name of the character (must not be null)
     * @param count the amount to increment (can be positive or negative)
     * @throws IllegalArgumentException if userIgn is null or blank
     * @see maple.expectation.infrastructure.queue.like.LikeSyncExecutor
     */
    fun incrementLikeCount(userIgn: String, count: Long)

    /**
     * Find characters by multiple user IGNs (batch query for micro-batching)
     *
     * <p>This method is optimized for bulk lookups used in adaptive micro-batching.
     *
     * @param userIgns list of in-game names to search for
     * @return Map of userIgn to GameCharacter (only found entries)
     */
    fun findByUserIgnIn(userIgns: List<String>): Map<String, GameCharacter>
}
