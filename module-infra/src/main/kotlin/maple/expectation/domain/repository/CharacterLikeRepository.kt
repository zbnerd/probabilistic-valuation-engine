package maple.expectation.domain.repository

import maple.expectation.core.domain.model.like.CharacterLike

/**
 * CharacterLike Repository Interface (Port)
 *
 * <p><b>Purpose:</b> Defines the contract for character "like" (favorite) persistence operations
 * following the Ports and Adapters pattern. This interface belongs to the domain layer and contains
 * no infrastructure dependencies.
 *
 * <p><b>Key Concepts:</b>
 *
 * <ul>
 *   <li>Prevents duplicate likes via UNIQUE constraint on (target_ocid, liker_account_id)
 *   <li>Supports pagination for user's liked characters list
 *   <li>Tracks statistics (total likes per character, total likes by user)
 * </ul>
 *
 * <p><b>Contract:</b>
 *
 * <ul>
 *   <li>All methods return domain entities, not implementation details
 *   <li>Optional is used for single-result queries that may return nothing
 *   <li>Empty collections are returned for list queries (never null)
 *   <li>Self-like prevention is handled at the service layer, not repository
 * </ul>
 *
 * <p><b>Usage Example:</b>
 *
 * <pre>{@code
 * // Check if user already liked a character
 * val existing = likeRepository.findByTargetOcidAndLikerAccountId("char123", "user456")
 *
 * // Save new like
 * val like = CharacterLike.of("char123", "user456")
 * likeRepository.save(like)
 *
 * // Get all likes by a user
 * val userLikes = likeRepository.findByLikerAccountId("user456")
 *
 * // Count total likes for a character
 * val totalLikes = likeRepository.countByTargetOcid("char123")
 *
 * // Unlike (delete)
 * likeRepository.delete(like)
 * }</pre>
 *
 * <p><b>Implementation Notes:</b>
 *
 * <ul>
 *   <li>Implementations must enforce UNIQUE constraint on (target_ocid, liker_account_id)
 *   <li>Implementations should use indexes: {@code idx_target_ocid}, {@code idx_liker_account_id}
 *   <li>Delete operations should be transactional
 * </ul>
 *
 * @see CharacterLike
 */
interface CharacterLikeRepository {

    /**
     * Find a like record by target character and liker account
     *
     * <p>This method is used to check for duplicate likes before creating a new one.
     *
     * @param targetOcid the OCID of the character being liked (must not be null)
     * @param likerAccountId the account ID of the user who liked (must not be null)
     * @return Optional containing the like record if found, empty otherwise
     * @throws IllegalArgumentException if targetOcid or likerAccountId is null/blank
     */
    fun findByTargetOcidAndLikerAccountId(targetOcid: String, likerAccountId: String): CharacterLike?

    /**
     * Find all likes given by a specific account
     *
     * <p>This method returns all characters liked by a user, ordered by creation time (newest first).
     *
     * @param likerAccountId the account ID of the user (must not be null)
     * @return List of likes (empty list if none exist, never null)
     * @throws IllegalArgumentException if likerAccountId is null/blank
     */
    fun findByLikerAccountId(likerAccountId: String): List<CharacterLike>

    /**
     * Find all likes received by a specific character
     *
     * <p>This method returns all users who liked a specific character.
     *
     * @param targetOcid the OCID of the character (must not be null)
     * @return List of likes (empty list if none exist, never null)
     * @throws IllegalArgumentException if targetOcid is null/blank
     */
    fun findByTargetOcid(targetOcid: String): List<CharacterLike>

    /**
     * Save a like record (create or update)
     *
     * <p><b>Note:</b> Due to the UNIQUE constraint, attempting to save a duplicate like will result
     * in an exception. Check existence first with {@code findByTargetOcidAndLikerAccountId}.
     *
     * @param like the like record to save (must not be null)
     * @return the saved like record with generated ID
     * @throws IllegalArgumentException if like is null
     * @throws org.springframework.dao.DataIntegrityViolationException if unique constraint is violated
     */
    fun save(like: CharacterLike): CharacterLike

    /**
     * Delete a like record
     *
     * <p>This method is idempotent - deleting a non-existent like has no effect.
     *
     * @param like the like record to delete (must not be null)
     * @throws IllegalArgumentException if like is null
     */
    fun delete(like: CharacterLike)

    /**
     * Delete a like by target character and liker account
     *
     * <p>This is a convenience method that combines finding and deleting.
     *
     * @param targetOcid the OCID of the character (must not be null)
     * @param likerAccountId the account ID of the user (must not be null)
     * @return number of deleted rows (0 if already deleted by concurrent request)
     * @throws IllegalArgumentException if targetOcid or likerAccountId is null/blank
     */
    fun deleteByTargetOcidAndLikerAccountId(targetOcid: String, likerAccountId: String): Long

    /**
     * Count total likes received by a specific character
     *
     * <p>This method is optimized for counting and should be preferred over loading all entities and
     * calling {@code List.size}.
     *
     * @param targetOcid the OCID of the character (must not be null)
     * @return the number of likes for the character
     * @throws IllegalArgumentException if targetOcid is null/blank
     */
    fun countByTargetOcid(targetOcid: String): Long

    /**
     * Count total likes given by a specific account
     *
     * <p>This method is optimized for counting and should be preferred over loading all entities and
     * calling {@code List.size}.
     *
     * @param likerAccountId the account ID of the user (must not be null)
     * @return the number of likes given by the user
     * @throws IllegalArgumentException if likerAccountId is null/blank
     */
    fun countByLikerAccountId(likerAccountId: String): Long

    /**
     * Check if a like exists
     *
     * <p>This method is more efficient than {@code findByTargetOcidAndLikerAccountId}
     * when you only need to check existence without loading the entity.
     *
     * @param targetOcid the OCID of the character (must not be null)
     * @param likerAccountId the account ID of the user (must not be null)
     * @return true if the like exists, false otherwise
     * @throws IllegalArgumentException if targetOcid or likerAccountId is null/blank
     */
    fun existsByTargetOcidAndLikerAccountId(targetOcid: String, likerAccountId: String): Boolean

    /**
     * Atomic INSERT with duplicate protection (ADR-029 Race Condition fix).
     *
     * <p>Uses INSERT ... ON CONFLICT DO NOTHING to prevent TOCTOU race conditions.
     *
     * @param targetOcid the OCID of the character being liked
     * @param likerAccountId the account ID of the user who liked
     * @return 1 if inserted, 0 if already exists
     */
    fun insertIfAbsent(targetOcid: String, likerAccountId: String): Int
}
