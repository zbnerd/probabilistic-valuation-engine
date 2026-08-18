package maple.nexon.client.byok

data class NexonCharacterList(
    val accounts: List<NexonAccount>,
) {
    val characters: List<NexonCharacter> = accounts.flatMap(NexonAccount::characters)
}

data class NexonAccount(
    val accountId: String?,
    val characters: List<NexonCharacter>,
)

data class NexonCharacter(
    val ocid: String?,
    val characterName: String?,
    val worldName: String?,
    val characterClass: String?,
    val characterLevel: Int,
)
