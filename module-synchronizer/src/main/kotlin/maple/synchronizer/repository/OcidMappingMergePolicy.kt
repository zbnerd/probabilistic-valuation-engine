package maple.synchronizer.repository

import maple.synchronizer.domain.OcidMapping
import org.springframework.stereotype.Component

/**
 * Merge policy for OCID mapping writes.
 *
 * Encapsulates the "delete-stale-then-upsert-new" strategy so the repository
 * stays focused on SQL execution. The policy is pure logic (no Spring, no JDBC,
 * no Redis) and is independently testable.
 */
@Component
class OcidMappingMergePolicy {
    /**
     * The split of a new mapping batch into:
     * - [ocidsToDelete]: existing ocids whose records must be removed before upsert
     *   (records whose ocid collides with an incoming mapping under a different user_ign)
     * - [mappingsToInsert]: the fresh records to upsert
     *
     * The caller (repository) issues the DELETE then UPSERT inside a single transaction.
     */
    data class MergePlan(
        val ocidsToDelete: List<String>,
        val mappingsToInsert: List<OcidMapping>,
    )

    fun plan(existing: List<OcidMapping>, incoming: List<OcidMapping>): MergePlan {
        val incomingOcids: Set<String> = incoming.map { it.ocid }.toSet()
        val ocidsToDelete: List<String> = existing
            .filter { existingRecord -> incomingOcids.contains(existingRecord.ocid) }
            .map { it.ocid }
        return MergePlan(
            ocidsToDelete = ocidsToDelete,
            mappingsToInsert = incoming,
        )
    }
}
