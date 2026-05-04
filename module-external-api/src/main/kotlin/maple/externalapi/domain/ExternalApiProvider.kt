package maple.externalapi.domain

enum class ExternalApiProvider(
    val baseUrl: String,
) {
    NEXON("https://open.api.nexon.com"),
}
