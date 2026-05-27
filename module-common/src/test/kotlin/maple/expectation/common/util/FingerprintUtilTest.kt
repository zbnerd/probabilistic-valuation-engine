package maple.expectation.common.util

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FingerprintUtilTest {

    private val secret = "test-secret-key-for-unit-testing-32ch"

    @Test
    fun `same API key produces same fingerprint`() {
        val fp1 = FingerprintUtil.generate("api-key-1", secret)
        val fp2 = FingerprintUtil.generate("api-key-1", secret)
        assertThat(fp1).isEqualTo(fp2)
    }

    @Test
    fun `different API keys produce different fingerprints`() {
        val fp1 = FingerprintUtil.generate("api-key-1", secret)
        val fp2 = FingerprintUtil.generate("api-key-2", secret)
        assertThat(fp1).isNotEqualTo(fp2)
    }

    @Test
    fun `verify returns true for matching key and secret`() {
        val fp = FingerprintUtil.generate("api-key-1", secret)
        assertThat(FingerprintUtil.verify("api-key-1", fp, secret)).isTrue()
    }

    @Test
    fun `verify returns false for wrong key`() {
        val fp = FingerprintUtil.generate("api-key-1", secret)
        assertThat(FingerprintUtil.verify("api-key-2", fp, secret)).isFalse()
    }

    @Test
    fun `verify returns false for wrong secret`() {
        val fp = FingerprintUtil.generate("api-key-1", secret)
        assertThat(FingerprintUtil.verify("api-key-1", fp, "different-secret-32-chars-long!!")).isFalse()
    }

    @Test
    fun `generate throws on blank API key`() {
        assertThrows<IllegalArgumentException> { FingerprintUtil.generate("", secret) }
        assertThrows<IllegalArgumentException> { FingerprintUtil.generate("   ", secret) }
    }

    @Test
    fun `output is valid base64url without padding`() {
        val fp = FingerprintUtil.generate("api-key-1", secret)
        assertThat('=' !in fp).isTrue()
        assertThat('+' !in fp).isTrue()
        assertThat('/' !in fp).isTrue()
        assertThat(Regex("[A-Za-z0-9_-]+").matches(fp)).isTrue()
    }
}
