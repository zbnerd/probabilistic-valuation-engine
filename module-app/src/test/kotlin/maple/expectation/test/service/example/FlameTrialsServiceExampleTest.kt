package maple.expectation.test.service.example

import maple.expectation.core.domain.flame.FlameEquipCategory
import maple.expectation.core.domain.flame.FlameType
import maple.expectation.core.flame.service.FlameTrialsService
import maple.expectation.core.probability.FlameDpCalculator
import maple.expectation.core.probability.FlameScoreCalculator
import maple.expectation.test.service.ServiceTestTemplate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.transaction.annotation.Transactional

/**
 * FlameTrialsService 테스트 예시
 *
 * <p>ServiceTestTemplate 사용 예제를 보여줍니다.
 *
 * <h3>테스트 패턴</h3>
 * <ul>
 *   <li>Given-When-Then 패턴 사용</li>
 *   <li>@Transactional 롤백 테스트</li>
 *   <li>Domain Service 로직 검증</li>
 *   <li>영속성 컨텍스트 제어 예시</li>
 * </ul>
 */
@Transactional
@DisplayName("Flame Trials Service 테스트 예시")
class FlameTrialsServiceExampleTest : ServiceTestTemplate() {

    @Autowired
    lateinit var flameTrialsService: FlameTrialsService

    @MockBean
    lateinit var dpCalculator: FlameDpCalculator

    @MockBean
    lateinit var scoreCalculator: FlameScoreCalculator

    @Test
    @DisplayName("환생의 불꽃 기대 시도 횟수 계산 성공")
    fun `환생의 불꽃 기대 시도 횟수 계산 성공`() {
        // Given
        val category = FlameEquipCategory.OTHER_WEAPON
        val flameType = FlameType.ABYSS
        val level = 250
        val weights = FlameScoreCalculator.JobWeights.of("STR", "DEX")
        val target = 300
        val baseAtt = 1000
        val baseMag = 500

        val expectedTrials = 150.0

        given(
            scoreCalculator.buildOptionPmfs(
                category,
                flameType,
                level,
                weights,
                baseAtt,
                baseMag,
            ),
        ).willReturn(emptyList())

        given(
            dpCalculator.calculateExpectedTrials(
                category,
                flameType,
                level,
                weights,
                target,
                baseAtt,
                baseMag,
                emptyList(),
            ),
        ).willReturn(expectedTrials)

        // When
        val result =
            flameTrialsService.calculateExpectedTrials(
                category,
                flameType,
                level,
                weights,
                target,
                baseAtt,
                baseMag,
            )

        // Then
        then(scoreCalculator).should().buildOptionPmfs(
            category,
            flameType,
            level,
            weights,
            baseAtt,
            baseMag,
        )
        then(dpCalculator).should().calculateExpectedTrials(
            category,
            flameType,
            level,
            weights,
            target,
            baseAtt,
            baseMag,
            emptyList(),
        )
        assertThat(result).isEqualTo(expectedTrials)
    }

    @Test
    @DisplayName("계산 실패 시 null 반환")
    fun `계산 실패 시 null 반환`() {
        // Given
        val category = FlameEquipCategory.OTHER_WEAPON
        val flameType = FlameType.ABYSS
        val level = 250
        val weights = FlameScoreCalculator.JobWeights.of("STR", "DEX")
        val target = 300
        val baseAtt = 1000
        val baseMag = 500

        given(
            scoreCalculator.buildOptionPmfs(
                category,
                flameType,
                level,
                weights,
                baseAtt,
                baseMag,
            ),
        ).willReturn(emptyList())

        given(
            dpCalculator.calculateExpectedTrials(
                category,
                flameType,
                level,
                weights,
                target,
                baseAtt,
                baseMag,
                emptyList(),
            ),
        ).willReturn(null)

        // When
        val result =
            flameTrialsService.calculateExpectedTrials(
                category,
                flameType,
                level,
                weights,
                target,
                baseAtt,
                baseMag,
            )

        // Then
        assertThat(result).isNull()
    }

    @Test
    @DisplayName("옵션 PMF 빌드 후 DP 계산 위임 확인")
    fun `옵션 PMF 빌드 후 DP 계산 위임 확인`() {
        // Given
        val category = FlameEquipCategory.BOSS_WEAPON
        val flameType = FlameType.ABYSS
        val level = 250
        val weights = FlameScoreCalculator.JobWeights.of("INT", "LUK")
        val target = 300
        val baseAtt = 1000
        val baseMag = 500

        val mockPmfs: List<Map<Int, Double>> =
            listOf(
                mapOf(1 to 0.1, 2 to 0.2, 3 to 0.3),
            )
        val expectedTrials = 120.0

        given(
            scoreCalculator.buildOptionPmfs(
                category,
                flameType,
                level,
                weights,
                baseAtt,
                baseMag,
            ),
        ).willReturn(mockPmfs)

        given(
            dpCalculator.calculateExpectedTrials(
                category,
                flameType,
                level,
                weights,
                target,
                baseAtt,
                baseMag,
                mockPmfs,
            ),
        ).willReturn(expectedTrials)

        // When
        val result =
            flameTrialsService.calculateExpectedTrials(
                category,
                flameType,
                level,
                weights,
                target,
                baseAtt,
                baseMag,
            )

        // Then
        then(dpCalculator).should().calculateExpectedTrials(
            category,
            flameType,
            level,
            weights,
            target,
            baseAtt,
            baseMag,
            mockPmfs,
        )
        assertThat(result).isEqualTo(expectedTrials)
    }
}
