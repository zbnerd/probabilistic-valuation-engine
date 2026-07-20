package maple.expectation.core.calculation.probability

data class ProbabilityTableVersion(
    val logical: String,
    val contentSha256: String,
) {
    init {
        require(logical.isNotBlank()) { "logical version must not be blank" }
        require(contentSha256.matches(SHA_256)) { "contentSha256 must be 64 lowercase hexadecimal characters" }
    }

    private companion object {
        val SHA_256 = Regex("[0-9a-f]{64}")
    }
}
