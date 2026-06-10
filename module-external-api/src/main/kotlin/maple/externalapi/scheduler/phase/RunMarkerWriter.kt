package maple.externalapi.scheduler.phase

import maple.expectation.common.storage.ObjectStorage
import java.time.Clock

/**
 * Writes a `_RUNNING` marker object for an in-progress run.
 * Stored as a small text object (`<runKey>/_RUNNING` containing the
 * `Clock.instant().toString()`). Existence is checked via
 * `ObjectStorage.exists()`; the content is informational only.
 */
class RunMarkerWriter(
    private val clock: Clock,
    private val objectStorage: ObjectStorage,
) {
    fun writeRunMarker(runKey: String) {
        val markerKey = "$runKey/_RUNNING"
        val payload = clock.instant().toString().toByteArray()
        objectStorage.put(markerKey, payload)
    }
}
