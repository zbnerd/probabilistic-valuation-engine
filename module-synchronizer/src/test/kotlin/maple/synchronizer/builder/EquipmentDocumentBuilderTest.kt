package maple.synchronizer.builder

import maple.synchronizer.domain.CalculatedEquipmentItem
import maple.synchronizer.domain.GroupedEquipmentResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class EquipmentDocumentBuilderTest {

    private val builder = EquipmentDocumentBuilder()

    @Test
    fun `should build document with userIgn`() {
        val grouped = GroupedEquipmentResult(
            readKey = "ocid1:1",
            ocid = "ocid1",
            presetNo = 1,
            userIgn = "진격캐넌",
            items = listOf(
                CalculatedEquipmentItem(
                    ocid = "ocid1", presetNo = 1, itemName = "item1",
                    itemLevel = 200, itemPart = "Weapon", itemEquipmentPart = null,
                    potentialGrade = null, potentialOptions = null,
                    additionalGrade = null, additionalOptions = null,
                    currentStar = 0, targetStar = 0, status = "SKIPPED",
                    totalCost = BigDecimal.ZERO, blackCubeCost = BigDecimal.ZERO,
                    additionalCubeCost = BigDecimal.ZERO, starforceCost = BigDecimal.ZERO,
                    errorMessage = null,
                )
            ),
        )

        val doc = builder.build("run1", "chunk1", grouped)
        assertThat(doc.userIgn).isEqualTo("진격캐넌")
        assertThat(doc.ocid).isEqualTo("ocid1")
        assertThat(doc.presetNo).isEqualTo(1)
    }

    @Test
    fun `should build document with null userIgn`() {
        val grouped = GroupedEquipmentResult(
            readKey = "ocid1:1",
            ocid = "ocid1",
            presetNo = 1,
            userIgn = null,
            items = emptyList(),
        )

        val doc = builder.build("run1", "chunk1", grouped)
        assertThat(doc.userIgn).isNull()
    }
}
