package maple.nexon.client.byok

import maple.nexon.client.model.NexonEndpointPurpose
import maple.nexon.client.model.NexonRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class NexonCharacterListTest {
    @Test
    fun `flattens accounts in source order without module-specific DTOs`() {
        val first = NexonCharacter("ocid-1", "First", "Scania", "Hero", 280)
        val second = NexonCharacter("ocid-2", "Second", null, null, 0)
        val response = NexonCharacterList(
            accounts = listOf(
                NexonAccount("account-1", listOf(first)),
                NexonAccount(null, listOf(second)),
            ),
        )

        assertThat(response.characters).containsExactly(first, second)
        assertThat(response.accounts.first().accountId).isEqualTo("account-1")
        assertThat(response.javaClass.declaredFields.map { it.type.name })
            .noneMatch { it.startsWith("maple.expectation") || it.startsWith("maple.externalapi") }
    }

    @Test
    fun `request requires absolute path and template`() {
        assertThatThrownBy {
            NexonRequest(NexonEndpointPurpose.CHARACTER_LIST, "relative", emptyMap(), "/template")
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            NexonRequest(NexonEndpointPurpose.CHARACTER_LIST, "/path", emptyMap(), "relative")
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
