package maple.synchronizer.storage

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ResultFileReaderTest {

    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val reader = ResultFileReader(basePath = "/nonexistent", maxRowsPerChunk = 100000, objectMapper = objectMapper)

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
    fun `parseItem - missing ocid throws`() {
        val line = """{"presetNo":1,"itemName":"Sword"}"""

        assertThatThrownBy { reader.parseItem(line) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Missing required field: ocid")
    }

    @Test
    fun `parseItem - missing presetNo throws`() {
        val line = """{"ocid":"oc1","itemName":"Sword"}"""

        assertThatThrownBy { reader.parseItem(line) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Missing required field: presetNo")
    }

    @Test
    fun `parseItem - invalid JSON throws`() {
        val line = "not json at all"

        assertThatThrownBy { reader.parseItem(line) }
            .isInstanceOf(Exception::class.java)
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
