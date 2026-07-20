package maple.pipeline.artifact.identity

@JvmInline
value class ArtifactSegment private constructor(val value: String) {
    companion object {
        fun require(raw: String): ArtifactSegment {
            require(raw.isNotBlank()) { "artifact segment must not be blank" }
            require('/' !in raw && '\\' !in raw && raw != "." && raw != "..") {
                "artifact segment must not contain path separators"
            }
            return ArtifactSegment(raw)
        }
    }
}
