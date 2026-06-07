package maple.cleanup.config

import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySource
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
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

    @Test
    fun `binds from yaml source with overrides`() {
        val source: ConfigurationPropertySource = MapConfigurationPropertySource(
            mapOf(
                "cleanup.dry-run" to "false",
                "cleanup.runs.keep-recent" to "3",
                "cleanup.runs.keep-within-hours" to "12",
                "cleanup.max-delete-runs-per-cycle" to "20",
            )
        )
        val bound: CleanupProperties = Binder(source)
            .bind("cleanup", CleanupProperties::class.java)
            .orElseThrow { IllegalStateException("bind failed") }
        assertEquals(false, bound.dryRun)
        assertEquals(3, bound.runs.keepRecent)
        assertEquals(12L, bound.runs.keepWithinHours)
        assertEquals(20, bound.maxDeleteRunsPerCycle)
    }
}
