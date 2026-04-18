package maple.expectation.web.dto.v4

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("EquipmentExpectationResponseV4 Builder null-safety tests")
class EquipmentExpectationResponseV4BuilderTest {

    @Test
    @DisplayName("필수 필드 누락 시 명시적인 IllegalArgumentException을 던진다")
    fun `builder should fail fast with required field message`() {
        assertThatThrownBy {
            EquipmentExpectationResponseV4.builder().build()
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Missing required field: userIgn")
    }

    @Test
    @DisplayName("모든 필수 필드가 있으면 DTO를 생성한다")
    fun `builder should build response when all required fields are present`() {
        val cube = EquipmentExpectationResponseV4.CubeExpectationDto.builder()
            .expectedCost(1.0)
            .expectedCostText("1")
            .expectedTrials(2.0)
            .currentGrade("UNIQUE")
            .targetGrade("LEGENDARY")
            .potential("STR%")
            .build()

        val starforce = EquipmentExpectationResponseV4.StarforceExpectationDto.builder()
            .currentStar(17)
            .targetStar(22)
            .isNoljang(false)
            .costWithoutDestroyPrevention(100.0)
            .costWithoutDestroyPreventionText("100")
            .expectedDestroyCountWithout(0.1)
            .costWithDestroyPrevention(120.0)
            .costWithDestroyPreventionText("120")
            .expectedDestroyCountWith(0.05)
            .build()

        val flame = EquipmentExpectationResponseV4.FlameExpectationDto.builder()
            .powerfulFlameTrials(10.0)
            .eternalFlameTrials(5.0)
            .abyssFlameTrials(3.0)
            .build()

        val item = EquipmentExpectationResponseV4.ItemExpectationV4.builder()
            .itemName("앱솔랩스 모자")
            .itemIcon("icon")
            .itemPart("모자")
            .itemLevel(160)
            .expectedCost(1000.0)
            .expectedCostText("1,000")
            .costBreakdown(EquipmentExpectationResponseV4.CostBreakdownDto(1.0, 2.0, 3.0, 4.0))
            .enhancePath("black_cube")
            .currentStar(17)
            .targetStar(22)
            .isNoljang(false)
            .specialRingLevel(0)
            .blackCubeExpectation(cube)
            .additionalCubeExpectation(cube)
            .starforceExpectation(starforce)
            .flameExpectation(flame)
            .build()

        val response = EquipmentExpectationResponseV4.builder()
            .userIgn("테스터")
            .calculatedAt(java.time.LocalDateTime.now())
            .fromCache(false)
            .totalExpectedCost(1000.0)
            .totalCostText("1,000")
            .totalCostBreakdown(EquipmentExpectationResponseV4.CostBreakdownDto(1.0, 2.0, 3.0, 4.0))
            .maxPresetNo(1)
            .presets(
                listOf(
                    EquipmentExpectationResponseV4.PresetExpectation(
                        presetNo = 1,
                        totalExpectedCost = 1000.0,
                        totalCostText = "1,000",
                        costBreakdown = EquipmentExpectationResponseV4.CostBreakdownDto(1.0, 2.0, 3.0, 4.0),
                        items = listOf(item),
                    ),
                ),
            )
            .build()

        assertThat(response.userIgn).isEqualTo("테스터")
        assertThat(response.presets).hasSize(1)
    }
}
