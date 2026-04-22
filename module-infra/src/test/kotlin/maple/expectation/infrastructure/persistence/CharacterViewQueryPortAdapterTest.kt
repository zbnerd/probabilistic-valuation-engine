package maple.expectation.infrastructure.persistence

import maple.expectation.core.domain.model.character.CharacterView
import maple.expectation.infrastructure.persistence.entity.CharacterValuationViewEntity
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.Optional
import org.assertj.core.api.Assertions.assertThat

/**
 * Unit tests for [CharacterViewQueryPortAdapter].
 *
 * <p><strong>Test Coverage:</strong>
 *
 * <ul>
 *   <li>findByUserIgn with null entity returns Optional.empty()</li>
 *   <li>findByUserIgn with entity returns Optional with correct CharacterView mapping</li>
 *   <li>Verify userIgn, messageId, totalExpectedCost, maxPresetNo map correctly</li>
 *   <li>Verify presets list maps correctly (with nested costBreakdown and items)</li>
 *   <li>deleteByUserIgn delegates to queryService</li>
 *   <li>upsertFromCalculation delegates to queryService with correct parameters</li>
 * </ul>
 *
 * @see CharacterViewQueryPortAdapter
 */
@Tag("unit")
@ExtendWith(MockitoExtension::class)
@DisplayName("CharacterViewQueryPortAdapter 단위 테스트")
class CharacterViewQueryPortAdapterTest {

    @Mock
    private lateinit var queryService: CharacterViewQueryServicePostgres

    private lateinit var adapter: CharacterViewQueryPortAdapter

    @BeforeEach
    fun setUp() {
        adapter = CharacterViewQueryPortAdapter(queryService)
    }

    @Test
    @DisplayName("findByUserIgn은 null entity면 Optional.empty를 반환한다")
    fun findByUserIgn_returnsEmptyWhenEntityNull() {
        val userIgn = "nonExistentUser"
        whenever(queryService.findByUserIgn(userIgn)).thenReturn(null)

        val result = adapter.findByUserIgn(userIgn)

        assertThat(result).isEmpty
        verify(queryService).findByUserIgn(userIgn)
    }

    @Test
    @DisplayName("findByUserIgn은 entity를 CharacterView로 매핑하여 반환한다")
    fun findByUserIgn_mapsEntityToCharacterView() {
        val userIgn = "testUser"
        val messageId = "msg-123"
        val totalExpectedCost = 1000000L
        val maxPresetNo = 3
        val entity = createTestEntity(
            userIgn = userIgn,
            messageId = messageId,
            totalExpectedCost = totalExpectedCost,
            maxPresetNo = maxPresetNo,
        )
        whenever(queryService.findByUserIgn(userIgn)).thenReturn(entity)

        val result = adapter.findByUserIgn(userIgn)

        assertThat(result).isPresent
        val view = result.get()
        assertThat(view.userIgn).isEqualTo(userIgn)
        assertThat(view.messageId).isEqualTo(messageId)
        assertThat(view.totalExpectedCost).isEqualTo(totalExpectedCost)
        assertThat(view.maxPresetNo).isEqualTo(maxPresetNo)
        assertThat(view.calculatedAt).isNotNull
        assertThat(view.fromCache).isNotNull
    }

    @Test
    @DisplayName("findByUserIgn은 presets를 올바르게 매핑한다")
    fun findByUserIgn_mapsPresetsCorrectly() {
        val userIgn = "testUser"
        val preset1 = createTestPreset(
            presetNo = 1,
            totalExpectedCost = 100000L,
            totalCostText = "10만",
            blackCubeCost = 10000L,
            redCubeCost = 20000L,
            itemName = "Item1",
            itemExpectedCost = 50000L,
        )
        val preset2 = createTestPreset(
            presetNo = 2,
            totalExpectedCost = 200000L,
            totalCostText = "20만",
            blackCubeCost = 30000L,
            redCubeCost = 40000L,
            itemName = "Item2",
            itemExpectedCost = 100000L,
        )
        val entity = createTestEntity(
            userIgn = userIgn,
            presets = listOf(preset1, preset2),
        )
        whenever(queryService.findByUserIgn(userIgn)).thenReturn(entity)

        val result = adapter.findByUserIgn(userIgn)

        assertThat(result).isPresent
        val view = result.get()
        assertThat(view.presets).hasSize(2)

        val viewPreset1 = view.presets!![0]
        assertThat(viewPreset1.presetNo).isEqualTo(1)
        assertThat(viewPreset1.totalExpectedCost).isEqualTo(100000L)
        assertThat(viewPreset1.totalCostText).isEqualTo("10만")
        assertThat(viewPreset1.costBreakdown?.blackCubeCost).isEqualTo(10000L)
        assertThat(viewPreset1.costBreakdown?.redCubeCost).isEqualTo(20000L)
        assertThat(viewPreset1.items).hasSize(1)
        assertThat(viewPreset1.items!![0].itemName).isEqualTo("Item1")
        assertThat(viewPreset1.items!![0].expectedCost).isEqualTo(50000L)

        val viewPreset2 = view.presets!![1]
        assertThat(viewPreset2.presetNo).isEqualTo(2)
        assertThat(viewPreset2.totalExpectedCost).isEqualTo(200000L)
        assertThat(viewPreset2.items!![0].itemName).isEqualTo("Item2")
    }

    @Test
    @DisplayName("findByUserIgn은 costBreakdown을 올바르게 매핑한다")
    fun findByUserIgn_mapsCostBreakdownCorrectly() {
        val userIgn = "testUser"
        val costBreakdown = CharacterValuationViewEntity.CostBreakdownView(
            blackCubeCost = 10000L,
            redCubeCost = 20000L,
            additionalCubeCost = 5000L,
            starforceCost = 30000L,
            flameCost = 35000L,
        )
        val preset = CharacterValuationViewEntity.PresetView(
            presetNo = 1,
            totalExpectedCost = 100000L,
            totalCostText = "10만",
            costBreakdown = costBreakdown,
            items = emptyList(),
        )
        val entity = createTestEntity(userIgn = userIgn, presets = listOf(preset))
        whenever(queryService.findByUserIgn(userIgn)).thenReturn(entity)

        val result = adapter.findByUserIgn(userIgn)

        assertThat(result).isPresent
        val view = result.get()
        val viewBreakdown = view.presets!![0].costBreakdown
        assertThat(viewBreakdown).isNotNull
        assertThat(viewBreakdown!!.blackCubeCost).isEqualTo(10000L)
        assertThat(viewBreakdown.redCubeCost).isEqualTo(20000L)
        assertThat(viewBreakdown.additionalCubeCost).isEqualTo(5000L)
        assertThat(viewBreakdown.starforceCost).isEqualTo(30000L)
        assertThat(viewBreakdown.flameCost).isEqualTo(35000L)
    }

    @Test
    @DisplayName("deleteByUserIgn은 queryService에 위임한다")
    fun deleteByUserIgn_delegatesToQueryService() {
        val userIgn = "testUser"

        adapter.deleteByUserIgn(userIgn)

        verify(queryService).deleteByUserIgn(userIgn)
    }

    @Test
    @DisplayName("upsertFromCalculation은 queryService에 올바른 파라미터로 위임한다")
    fun upsertFromCalculation_delegatesToQueryService() {
        val userIgn = "testUser"
        val messageId = "msg-123"
        val characterOcid = "ocid-456"
        val characterClass = "전체계산가"
        val characterLevel = 300
        val totalExpectedCost = 1000000L
        val maxPresetNo = 3
        val presetsJson = """[]"""

        adapter.upsertFromCalculation(
            userIgn,
            messageId,
            characterOcid,
            characterClass,
            characterLevel,
            totalExpectedCost,
            maxPresetNo,
            1, // presetNo default
            presetsJson,
        )

        verify(queryService).upsertFromCalculation(
            eq(userIgn),
            eq(messageId),
            eq(characterOcid),
            eq(characterClass),
            eq(characterLevel),
            eq(totalExpectedCost),
            eq(maxPresetNo),
            eq(1), // presetNo default
            eq(presetsJson),
        )
    }

    @Test
    @DisplayName("findByUserIgn은 null 필드를 처리한다")
    fun findByUserIgn_handlesNullFields() {
        val userIgn = "testUser"
        val entity = CharacterValuationViewEntity(
            id = 1L,
            jpaVersion = 0L,
            userIgn = userIgn,
            messageId = null,
            characterOcid = null,
            characterClass = null,
            characterLevel = null,
            calculatedAt = null,
            lastApiSyncAt = null,
            version = null,
            lastAppliedVersion = null,
            totalExpectedCost = null,
            maxPresetNo = null,
            presets = null,
            fromCache = null,
        )
        whenever(queryService.findByUserIgn(userIgn)).thenReturn(entity)

        val result = adapter.findByUserIgn(userIgn)

        assertThat(result).isPresent
        val view = result.get()
        assertThat(view.userIgn).isEqualTo(userIgn)
        assertThat(view.messageId).isNull()
        assertThat(view.totalExpectedCost).isNull()
        assertThat(view.maxPresetNo).isNull()
        assertThat(view.calculatedAt).isNull()
        assertThat(view.fromCache).isNull()
        assertThat(view.presets).isNull()
    }

    private fun createTestEntity(
        userIgn: String,
        messageId: String? = "msg-123",
        totalExpectedCost: Long = 1000000L,
        maxPresetNo: Int = 3,
        presets: List<CharacterValuationViewEntity.PresetView>? = null,
    ): CharacterValuationViewEntity {
        return CharacterValuationViewEntity(
            id = 1L,
            jpaVersion = 0L,
            userIgn = userIgn,
            messageId = messageId,
            characterOcid = "ocid-123",
            characterClass = "전체계산가",
            characterLevel = 300,
            calculatedAt = Instant.now(),
            lastApiSyncAt = Instant.now(),
            version = 1L,
            lastAppliedVersion = 1L,
            totalExpectedCost = totalExpectedCost,
            maxPresetNo = maxPresetNo,
            presets = presets,
            fromCache = false,
        )
    }

    private fun createTestPreset(
        presetNo: Int,
        totalExpectedCost: Long,
        totalCostText: String,
        blackCubeCost: Long,
        redCubeCost: Long,
        itemName: String,
        itemExpectedCost: Long,
    ): CharacterValuationViewEntity.PresetView {
        val costBreakdown = CharacterValuationViewEntity.CostBreakdownView(
            blackCubeCost = blackCubeCost,
            redCubeCost = redCubeCost,
            additionalCubeCost = 5000L,
            starforceCost = 30000L,
            flameCost = 35000L,
        )
        val item = CharacterValuationViewEntity.ItemExpectationView(
            itemName = itemName,
            expectedCost = itemExpectedCost,
            costText = "${itemExpectedCost / 10000}만",
        )
        return CharacterValuationViewEntity.PresetView(
            presetNo = presetNo,
            totalExpectedCost = totalExpectedCost,
            totalCostText = totalCostText,
            costBreakdown = costBreakdown,
            items = listOf(item),
        )
    }
}
