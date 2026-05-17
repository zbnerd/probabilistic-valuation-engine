package maple.externalapi.domain

data class ExternalApiFetchCommand(
    val jobId: String,
    val provider: ExternalApiProvider,
    val endpoint: ExternalApiEndpoint,
    val requestKey: String,
    val characterName: String? = null,
    val ocid: String? = null,
)

enum class ExternalApiEndpoint(
    val path: String,
    val keyType: KeyType,
) {
    OCID_LOOKUP("/maplestory/v1/id", KeyType.USER_IGN),
    CHARACTER_BASIC("/maplestory/v1/character/basic", KeyType.OCID),
    ITEM_EQUIPMENT("/maplestory/v1/character/item-equipment", KeyType.OCID),
    ;

    fun storageSubDir(): String = name.lowercase().replace('_', '-')
}

enum class KeyType {
    USER_IGN,
    OCID,
}
