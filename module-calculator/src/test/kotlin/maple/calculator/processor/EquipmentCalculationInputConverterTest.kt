package maple.calculator.processor

import maple.expectation.core.dto.cube.CubeCalculationInput
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset.offset
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class EquipmentCalculationInputConverterTest {

    private val converter = EquipmentCalculationInputConverter

    private fun cubeInput(block: CubeCalculationInput.() -> Unit): CubeCalculationInput = CubeCalculationInput().apply(block)

    @Nested
    inner class TargetStarTest {

        @Test
        fun `returns 0 when starforce is 0`() {
            val input = cubeInput {
                starforce = 0
                itemName = "소드"
                level = 150
            }
            assertThat(converter.targetStar(input)).isEqualTo(0)
        }

        @Test
        fun `returns 0 when starforce is negative`() {
            val input = cubeInput {
                starforce = -1
                itemName = "소드"
                level = 150
            }
            assertThat(converter.targetStar(input)).isEqualTo(0)
        }

        @Test
        fun `returns 0 when itemName is null`() {
            val input = cubeInput {
                starforce = 17
                itemName = null
                level = 150
            }
            assertThat(converter.targetStar(input)).isEqualTo(0)
        }

        @Test
        fun `returns 0 when itemName is blank`() {
            val input = cubeInput {
                starforce = 17
                itemName = "  "
                level = 150
            }
            assertThat(converter.targetStar(input)).isEqualTo(0)
        }

        @Test
        fun `returns 0 when level is 0`() {
            val input = cubeInput {
                starforce = 17
                itemName = "소드"
                level = 0
            }
            assertThat(converter.targetStar(input)).isEqualTo(0)
        }

        @Test
        fun `returns starforce for normal equipment`() {
            val input = cubeInput {
                starforce = 17
                itemName = "소드"
                level = 150
            }
            assertThat(converter.targetStar(input)).isEqualTo(17)
        }

        @Test
        fun `caps at MAX_NOLJANG_STAR for noljang equipment`() {
            val input = cubeInput {
                starforce = 25
                itemName = "소드"
                level = 150
                starforceScrollFlag = "사용"
            }
            assertThat(converter.targetStar(input)).isEqualTo(15)
        }

        @Test
        fun `returns starforce for noljang equipment below cap`() {
            val input = cubeInput {
                starforce = 10
                itemName = "소드"
                level = 150
                starforceScrollFlag = "사용"
            }
            assertThat(converter.targetStar(input)).isEqualTo(10)
        }
    }

    @Nested
    inner class ToCalculationInputTest {

        @Test
        fun `maps all fields for normal equipment`() {
            val cubeInput = cubeInput {
                itemName = "아케인소드"
                level = 200
                part = "무기"
                itemEquipmentPart = "한손검"
                itemIcon = "https://icon.url"
                grade = "유니크"
                options = mutableListOf("공격력 +6%", "보스 공격력 +30%", null)
                additionalGrade = "에픽"
                additionalOptions = mutableListOf("올스탯 +3%")
                starforce = 17
                starforceScrollFlag = "미사용"
            }

            val result = converter.toCalculationInput(cubeInput, presetNo = 2)

            assertThat(result.itemName).isEqualTo("아케인소드")
            assertThat(result.itemLevel).isEqualTo(200)
            assertThat(result.itemPart).isEqualTo("무기")
            assertThat(result.itemEquipmentPart).isEqualTo("한손검")
            assertThat(result.itemIcon).isEqualTo("https://icon.url")
            assertThat(result.presetNo).isEqualTo(2)
            assertThat(result.isNoljang).isFalse()
            assertThat(result.potentialGrade).isEqualTo("유니크")
            assertThat(result.potentialOptions).containsExactly("공격력 +6%", "보스 공격력 +30%")
            assertThat(result.additionalPotentialGrade).isEqualTo("에픽")
            assertThat(result.additionalPotentialOptions).containsExactly("올스탯 +3%")
            assertThat(result.currentStar).isEqualTo(0)
            assertThat(result.targetStar).isEqualTo(17)
        }

        @Test
        fun `resolves potentialPart for force shield`() {
            val cubeInput = cubeInput {
                part = "보조무기"
                itemEquipmentPart = "포스실드"
            }
            assertThat(converter.toCalculationInput(cubeInput, 1).itemPart).isEqualTo("포스실드")
        }

        @Test
        fun `resolves potentialPart for soul ring`() {
            val cubeInput = cubeInput {
                part = "보조무기"
                itemEquipmentPart = "소울링"
            }
            assertThat(converter.toCalculationInput(cubeInput, 1).itemPart).isEqualTo("포스실드")
        }

        @Test
        fun `resolves potentialPart for standard secondary weapon`() {
            val cubeInput = cubeInput {
                part = "보조무기"
                itemEquipmentPart = "블레이드"
            }
            assertThat(converter.toCalculationInput(cubeInput, 1).itemPart).isEqualTo("보조무기")
        }

        @Test
        fun `leaves part unchanged for non-secondary weapon`() {
            val cubeInput = cubeInput {
                part = "모자"
                itemEquipmentPart = "모자"
            }
            assertThat(converter.toCalculationInput(cubeInput, 1).itemPart).isEqualTo("모자")
        }

        @Test
        fun `sets isNoljang true when starforceScrollFlag is 사용`() {
            val cubeInput = cubeInput { starforceScrollFlag = "사용" }
            assertThat(converter.toCalculationInput(cubeInput, 1).isNoljang).isTrue()
        }

        @Test
        fun `filters null options from potential options`() {
            val cubeInput = cubeInput { options = mutableListOf(null, "공격력 +6%", null) }
            assertThat(converter.toCalculationInput(cubeInput, 1).potentialOptions).containsExactly("공격력 +6%")
        }

        @Test
        fun `handles all null fields`() {
            val cubeInput = cubeInput {
                itemName = null
                part = null
                itemEquipmentPart = null
                itemIcon = null
                grade = null
                options = mutableListOf()
                additionalGrade = null
                additionalOptions = mutableListOf()
            }

            val result = converter.toCalculationInput(cubeInput, 1)

            assertThat(result.itemName).isEqualTo("")
            assertThat(result.itemPart).isEmpty()
            assertThat(result.itemEquipmentPart).isEmpty()
            assertThat(result.itemIcon).isEmpty()
        }
    }

    @Nested
    inner class ToCalculationResultTest {

        @Test
        fun `maps all fields with SUCCESS status and costs`() {
            val cubeInput = cubeInput {
                itemName = "아케인소드"
                level = 200
                part = "무기"
                itemEquipmentPart = "한손검"
                grade = "유니크"
                options = mutableListOf("공격력 +6%", "보스 공격력 +30%", "공격력 +9%")
                additionalGrade = "에픽"
                additionalOptions = mutableListOf("올스탯 +3%", "올스탯 +3%", "올스탯 +3%")
                starforce = 17
                starforceScrollFlag = "미사용"
            }
            val costs = CalculationCache.ComponentCosts(
                blackCubeCost = 1.23,
                additionalCubeCost = 4.56,
                starforceCost = 7.89,
            )

            val result = converter.toCalculationResult("abc123", 3, cubeInput, costs, "SUCCESS", null)

            assertThat(result.ocid).isEqualTo("abc123")
            assertThat(result.presetNo).isEqualTo(3)
            assertThat(result.itemName).isEqualTo("아케인소드")
            assertThat(result.itemLevel).isEqualTo(200)
            assertThat(result.itemPart).isEqualTo("무기")
            assertThat(result.itemEquipmentPart).isEqualTo("한손검")
            assertThat(result.potentialGrade).isEqualTo("유니크")
            assertThat(result.potentialOptions).containsExactly("공격력 +6%", "보스 공격력 +30%", "공격력 +9%")
            assertThat(result.additionalGrade).isEqualTo("에픽")
            assertThat(result.additionalOptions).containsExactly("올스탯 +3%", "올스탯 +3%", "올스탯 +3%")
            assertThat(result.currentStar).isEqualTo(0)
            assertThat(result.targetStar).isEqualTo(17)
            assertThat(result.status).isEqualTo("SUCCESS")
            assertThat(result.totalCost).isCloseTo(13.68, offset(0.01))
            assertThat(result.blackCubeCost).isEqualTo(1.23)
            assertThat(result.additionalCubeCost).isEqualTo(4.56)
            assertThat(result.starforceCost).isEqualTo(7.89)
            assertThat(result.errorMessage).isNull()
        }

        @Test
        fun `maps ERROR status with message and empty costs`() {
            val cubeInput = cubeInput {
                itemName = "소드"
                level = 150
                part = "무기"
            }

            val result = converter.toCalculationResult("abc", 1, cubeInput, CalculationCache.ComponentCosts.empty(), "ERROR", "calculation failed")

            assertThat(result.status).isEqualTo("ERROR")
            assertThat(result.errorMessage).isEqualTo("calculation failed")
            assertThat(result.totalCost).isNull()
        }

        @Test
        fun `maps SKIPPED status with empty costs`() {
            val cubeInput = cubeInput {
                itemName = "소드"
                level = 150
                part = "모자"
                starforce = 0
            }

            val result = converter.toCalculationResult("xyz", 1, cubeInput, CalculationCache.ComponentCosts.empty(), "SKIPPED", null)

            assertThat(result.status).isEqualTo("SKIPPED")
            assertThat(result.totalCost).isNull()
        }

        @Test
        fun `handles null cubeInput fields`() {
            val cubeInput = cubeInput {
                itemName = null
                part = null
                grade = null
                options = mutableListOf()
                additionalGrade = null
                additionalOptions = mutableListOf()
            }

            val result = converter.toCalculationResult("abc", 1, cubeInput, CalculationCache.ComponentCosts.empty(), "SKIPPED", null)

            assertThat(result.itemName).isEqualTo("")
            assertThat(result.itemPart).isNull()
            assertThat(result.potentialGrade).isNull()
            assertThat(result.additionalGrade).isNull()
        }
    }
}
