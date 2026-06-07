package maple.cleanup.config

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CleanupPropertiesTest {
    @Test
    fun `binds all cleanup config with sensible defaults`() {
        val props = CleanupProperties()
        assertTrue(props.dryRun)
        assertEquals(5, props.runs.keepRecent)
        assertEquals(48L, props.runs.keepWithinHours)
        assertEquals(10, props.maxDeleteRunsPerCycle)
        assertEquals(5L * 1024 * 1024 * 1024, props.maxDeleteBytesPerCycle)
        assertEquals(60L, props.maxRuntimeSeconds)
    }
}
