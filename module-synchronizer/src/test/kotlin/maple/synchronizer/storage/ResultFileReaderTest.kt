package maple.synchronizer.storage

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ResultFileReaderTest {

    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val reader = ResultFileReader(basePath = "/nonexistent", objectMapper = objectMapper)

    @Test
    fun `parseItem - valid JSON line produces item`() {
        val line = """{"ocid":"oc1","presetNo":1,"itemName":"Sword","itemLevel":160,"itemPart":"Weapon","status":"SUCCESS","totalCost":1000}"""

        val item = reader.parseItem(line)

        assertThat(item).isNotNull
        assertThat(item!!.ocid).isEqualTo("oc1")
        assertThat(item.presetNo).isEqualTo(1)
        assertThat(item.itemName).isEqualTo("Sword")
        assertThat(item.itemLevel).isEqualTo(160)
        assertThat(item.status).isEqualTo("SUCCESS")
        assertThat(item.totalCost).isEqualByComparingTo(BigDecimal("1000"))
    }

    @Test
    fun `parseItem - missing ocid returns null`() {
        val line = """{"presetNo":1,"itemName":"Sword"}"""

        val item = reader.parseItem(line)

        assertThat(item).isNull()
    }

    @Test
    fun `parseItem - missing presetNo returns null`() {
        val line = """{"ocid":"oc1","itemName":"Sword"}"""

        val item = reader.parseItem(line)

        assertThat(item).isNull()
    }

    @Test
    fun `parseItem - invalid JSON returns null`() {
        val line = "not json at all"

        val item = reader.parseItem(line)

        assertThat(item).isNull()
    }

    @Test
    fun `parseItem - uses defaults for missing optional fields`() {
        val line = """{"ocid":"oc1","presetNo":2}"""

        val item = reader.parseItem(line)

        assertThat(item).isNotNull
        assertThat(item!!.itemName).isEmpty()
        assertThat(item.itemLevel).isEqualTo(0)
        assertThat(item.totalCost).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(item.status).isEqualTo("UNKNOWN")
        assertThat(item.errorMessage).isNull()
    }

    @Test
    fun `readAndGroupByCompositeKey - nonexistent file throws`() {
        assertThatThrownBy { reader.readAndGroupByCompositeKey("missing.jsonl.gz") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Result file not found")
    }
}
