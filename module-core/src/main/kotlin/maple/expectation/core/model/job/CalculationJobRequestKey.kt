package maple.expectation.core.model.job

import java.util.Locale

object CalculationJobRequestKey {
    private const val VERSION = "v1"
    private const val SCHEMA_VERSION = 1

    fun of(userIgn: String, presetNo: Int): String {
        val normalizedUserIgn = userIgn.trim().lowercase(Locale.ROOT)
        return "calc:$VERSION:ign:$normalizedUserIgn:preset:$presetNo:schema:$SCHEMA_VERSION"
    }
}
