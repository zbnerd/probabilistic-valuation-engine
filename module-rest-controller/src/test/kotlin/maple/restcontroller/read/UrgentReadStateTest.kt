package maple.restcontroller.read

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class UrgentReadStateTest {

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    @Test
    fun `fromName round-trips all four names`() {
        assertThat(UrgentReadState.fromName("READY")).isEqualTo(UrgentReadState.Ready)
        assertThat(UrgentReadState.fromName("NOT_FOUND")).isEqualTo(UrgentReadState.NotFound)
        assertThat(UrgentReadState.fromName("PENDING")).isEqualTo(UrgentReadState.Pending(null, null))
        assertThat(UrgentReadState.fromName("UNKNOWN")).isEqualTo(UrgentReadState.Unknown)
    }

    @Test
    fun `fromName throws IllegalArgumentException on unknown value`() {
        val ex = assertThrows<IllegalArgumentException> {
            UrgentReadState.fromName("BOGUS")
        }
        assertThat(ex.message).contains("BOGUS")
    }

    @Test
    fun `Ready returns 0 retry-after and does not try DB`() {
        assertThat(UrgentReadState.Ready.retryAfterSeconds(configDefault = 30L)).isEqualTo(0L)
        assertThat(UrgentReadState.Ready.shouldTryDb()).isFalse()
    }

    @Test
    fun `NotFound returns 0 retry-after and does not try DB`() {
        assertThat(UrgentReadState.NotFound.retryAfterSeconds(configDefault = 30L)).isEqualTo(0L)
        assertThat(UrgentReadState.NotFound.shouldTryDb()).isFalse()
    }

    @Test
    fun `Pending returns config retry-after and tries DB`() {
        val pending = UrgentReadState.Pending(queuePositionApprox = 5L, estimatedWaitSeconds = 30L)
        assertThat(pending.retryAfterSeconds(configDefault = 10L)).isEqualTo(10L)
        assertThat(pending.shouldTryDb()).isTrue()
        assertThat(pending.queuePositionApprox).isEqualTo(5L)
        assertThat(pending.estimatedWaitSeconds).isEqualTo(30L)
    }

    @Test
    fun `Pending with null position has null position fields`() {
        val pending = UrgentReadState.Pending(queuePositionApprox = null, estimatedWaitSeconds = null)
        assertThat(pending.queuePositionApprox).isNull()
        assertThat(pending.estimatedWaitSeconds).isNull()
        assertThat(pending.shouldTryDb()).isTrue()
    }

    @Test
    fun `Unknown returns config retry-after and tries DB`() {
        assertThat(UrgentReadState.Unknown.retryAfterSeconds(configDefault = 30L)).isEqualTo(30L)
        assertThat(UrgentReadState.Unknown.shouldTryDb()).isTrue()
    }

    @Test
    fun `name property on each subtype matches NAME constant`() {
        assertThat(UrgentReadState.Ready.name).isEqualTo("READY")
        assertThat(UrgentReadState.NotFound.name).isEqualTo("NOT_FOUND")
        assertThat(UrgentReadState.Pending(null, null).name).isEqualTo("PENDING")
        assertThat(UrgentReadState.Unknown.name).isEqualTo("UNKNOWN")
    }

    @Test
    fun `Jackson serialization produces name string for all four states`() {
        assertThat(mapper.writeValueAsString(UrgentReadState.Ready)).isEqualTo("\"READY\"")
        assertThat(mapper.writeValueAsString(UrgentReadState.NotFound)).isEqualTo("\"NOT_FOUND\"")
        assertThat(mapper.writeValueAsString(UrgentReadState.Pending(5L, 30L))).isEqualTo("\"PENDING\"")
        assertThat(mapper.writeValueAsString(UrgentReadState.Unknown)).isEqualTo("\"UNKNOWN\"")
    }

    @Test
    fun `UrgentReadStatusResponse JSON contains state name string`() {
        val response = UrgentReadStatusResponse(
            state = UrgentReadState.Pending(queuePositionApprox = 5L, estimatedWaitSeconds = 30L),
            userIgn = "test",
            statusUrl = "/api/v6/characters/test/status?presetNo=1",
            queuePositionApprox = 5L,
            estimatedWaitSeconds = 30L,
            retryAfterSeconds = 10L,
        )
        val json = mapper.writeValueAsString(response)
        assertThat(json).contains("\"state\":\"PENDING\"")
        assertThat(json).contains("\"userIgn\":\"test\"")
    }
}
