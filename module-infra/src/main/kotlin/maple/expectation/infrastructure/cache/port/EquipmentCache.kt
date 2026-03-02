package maple.expectation.infrastructure.cache.port

import maple.expectation.infrastructure.external.dto.v2.EquipmentResponse
import java.util.Optional

/**
 * Equipment Cache Port (DIP Interface)
 *
 * <p>Infrastructure layer interface for equipment caching operations. This abstraction allows AOP
 * aspects (infrastructure concern) to depend on an interface rather than concrete implementation,
 * following DIP principle.
 *
 * <h3>Dependency Direction:</h3>
 *
 * <pre>
 * module-infra (this interface) ← depended by
 * module-app (EquipmentCacheService implementation)
 * </pre>
 *
 * <h3>Methods Used By:</h3>
 *
 * <ul>
 *   <li>{@code NexonDataCacheAspect} - distributed caching coordination</li>
 * </ul>
 *
 * @see maple.expectation.infrastructure.cache.tiered.EquipmentCacheService
 */
interface EquipmentCache {

    /**
     * Retrieve valid cached equipment data (L1 → L2 → Warm-up)
     *
     * @param ocid character OCID
     * @return cached equipment if present and valid
     */
    fun getValidCache(ocid: String): Optional<EquipmentResponse>?

    /**
     * Check if negative cache exists for the OCID
     *
     * @param ocid character OCID
     * @return true if negative cache marker exists
     */
    fun hasNegativeCache(ocid: String): Boolean

    /**
     * Save equipment data to cache with async DB persistence
     *
     * @param ocid character OCID
     * @param response equipment response to cache (null to store negative marker)
     */
    fun saveCache(ocid: String, response: EquipmentResponse?)
}
