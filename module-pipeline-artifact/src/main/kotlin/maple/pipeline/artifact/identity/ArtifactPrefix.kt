package maple.pipeline.artifact.identity

@JvmInline
value class ArtifactPrefix private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Result<ArtifactPrefix> = runCatching {
            require(raw.endsWith('/')) { "artifact prefix must end with one slash" }
            require(!raw.endsWith("//")) { "artifact prefix must end with exactly one slash" }
            ArtifactKey.require(raw.dropLast(1))
            ArtifactPrefix(raw)
        }

        fun require(raw: String): ArtifactPrefix = parse(raw).getOrThrow()
    }

    override fun toString(): String = value
}

fun ArtifactKey.asPrefix(): ArtifactPrefix = ArtifactPrefix.require("$value/")
