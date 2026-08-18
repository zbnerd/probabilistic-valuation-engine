package maple.pipeline.artifact.identity

@JvmInline
value class ArtifactKey private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Result<ArtifactKey> = runCatching {
            require(raw.isNotBlank()) { "artifact key must not be blank" }
            require(!raw.startsWith('/')) { "artifact key must be relative" }
            require('\\' !in raw) { "artifact key must use forward slashes" }
            require(raw.split('/').none { it.isBlank() || it == "." || it == ".." }) {
                "artifact key contains an invalid segment"
            }
            ArtifactKey(raw)
        }

        fun require(raw: String): ArtifactKey = parse(raw).getOrThrow()
    }

    override fun toString(): String = value
}
