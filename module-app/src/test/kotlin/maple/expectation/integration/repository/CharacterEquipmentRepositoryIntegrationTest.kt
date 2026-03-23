package maple.expectation.integration.repository

import java.time.Duration
import java.time.LocalDateTime
import maple.expectation.core.domain.model.character.CharacterId
import maple.expectation.core.domain.model.equipment.CharacterEquipment
import maple.expectation.core.domain.model.equipment.EquipmentData
import maple.expectation.domain.repository.CharacterEquipmentRepository
import maple.expectation.test.RepositoryIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * CharacterEquipmentRepository 통합 테스트
 *
 * <p>모든 CharacterEquipmentRepository 메서드의 통합 동작을 검증합니다.
 *
 * <h3>테스트 커버리지</h3>
 * <ul>
 *   <li>findById - 캐릭터 ID로 장비 조회
 *   <li>save - 신규 장비 생성
 *   <li>saveAll - 일괄 장비 저장
 *   <li>deleteById - 캐릭터 ID로 장비 삭제
 *   <li>existsById - 캐릭터 ID로 존재 여부 확인
 * </ul>
 *
 * @see CharacterEquipmentRepository
 * @see CharacterEquipment
 */
@Tag("integration")
@Tag("repository")
@DisplayName("CharacterEquipmentRepository 통합 테스트")
class CharacterEquipmentRepositoryIntegrationTest : RepositoryIntegrationTestBase() {

    @Autowired
    private lateinit var characterEquipmentRepository: CharacterEquipmentRepository

    @Test
    @DisplayName("findById: 존재하는 캐릭터의 장비를 조회한다")
    fun `findById는 존재하는 캐릭터의 장비를 반환한다`() {
        // Arrange
        val characterId = CharacterId.of("test-ocid-001")
        val equipment = createTestEquipment(characterId, """{"weapon": "sword"}""")
        characterEquipmentRepository.save(equipment)
        flushAndClear()

        // Act
        val found = characterEquipmentRepository.findById(characterId)

        // Assert
        assertThat(found).isNotNull
        assertThat(found!!.characterId).isEqualTo(characterId)
        assertThat(found.jsonContent()).isEqualTo("""{"weapon": "sword"}""")
        assertThat(found.hasData()).isTrue
    }

    @Test
    @DisplayName("findById: 존재하지 않는 캐릭터 ID로 조회하면 null을 반환한다")
    fun `findById는 존재하지 않는 캐릭터 ID에 대해 null을 반환한다`() {
        // Arrange
        val nonExistentId = CharacterId.of("non-existent-ocid")

        // Act
        val found = characterEquipmentRepository.findById(nonExistentId)

        // Assert
        assertThat(found).isNull()
    }

    @Test
    @DisplayName("save: 신규 장비를 생성한다")
    fun `save는 신규 장비를 생성한다`() {
        // Arrange
        val characterId = CharacterId.of("test-ocid-002")
        val equipmentData = EquipmentData.of("""{"armor": "plate"}""")
        val newEquipment = CharacterEquipment.create(characterId, equipmentData)

        // Act
        val saved = characterEquipmentRepository.save(newEquipment)
        flushAndClear()

        // Assert
        assertThat(saved.characterId).isEqualTo(characterId)
        assertThat(saved.jsonContent()).isEqualTo("""{"armor": "plate"}""")
        assertThat(saved.updatedAt).isNotNull()
        assertThat(saved.hasData()).isTrue

        // DB에서 실제로 조회되는지 확인
        val found = characterEquipmentRepository.findById(characterId)
        assertThat(found).isNotNull
        assertThat(found!!.characterId).isEqualTo(characterId)
        assertThat(found.jsonContent()).isEqualTo("""{"armor": "plate"}""")
    }

    @Test
    @DisplayName("save: 빈 장비를 생성한다")
    fun `save는 빈 장비를 생성할 수 있다`() {
        // Arrange
        val characterId = CharacterId.of("test-ocid-003")
        val emptyEquipment = CharacterEquipment.createEmpty(characterId)

        // Act
        val saved = characterEquipmentRepository.save(emptyEquipment)
        flushAndClear()

        // Assert
        assertThat(saved.characterId).isEqualTo(characterId)
        assertThat(saved.isEmpty()).isTrue
        assertThat(saved.jsonContent()).isEqualTo("{}")

        // DB에서 실제로 조회되는지 확인
        val found = characterEquipmentRepository.findById(characterId)
        assertThat(found).isNotNull
        assertThat(found!!.isEmpty()).isTrue
    }

    @Test
    @DisplayName("saveAll: 여러 장비를 일괄 저장한다")
    fun `saveAll은 여러 장비를 일괄 저장한다`() {
        // Arrange
        val equipments = listOf(
            createTestEquipment(CharacterId.of("test-ocid-004"), """{"weapon": "sword"}"""),
            createTestEquipment(CharacterId.of("test-ocid-005"), """{"weapon": "bow"}"""),
            createTestEquipment(CharacterId.of("test-ocid-006"), """{"weapon": "staff"}"""),
        )

        // Act
        val saved = characterEquipmentRepository.saveAll(equipments)
        flushAndClear()

        // Assert
        assertThat(saved).hasSize(3)
        assertThat(saved[0].characterId.value).isEqualTo("test-ocid-004")
        assertThat(saved[1].characterId.value).isEqualTo("test-ocid-005")
        assertThat(saved[2].characterId.value).isEqualTo("test-ocid-006")

        // DB에서 실제로 조회되는지 확인
        val found1 = characterEquipmentRepository.findById(CharacterId.of("test-ocid-004"))
        val found2 = characterEquipmentRepository.findById(CharacterId.of("test-ocid-005"))
        val found3 = characterEquipmentRepository.findById(CharacterId.of("test-ocid-006"))

        assertThat(found1).isNotNull
        assertThat(found2).isNotNull
        assertThat(found3).isNotNull
        assertThat(found1!!.jsonContent()).isEqualTo("""{"weapon": "sword"}""")
        assertThat(found2!!.jsonContent()).isEqualTo("""{"weapon": "bow"}""")
        assertThat(found3!!.jsonContent()).isEqualTo("""{"weapon": "staff"}""")
    }

    @Test
    @DisplayName("saveAll: 빈 리스트를 저장하면 빈 리스트를 반환한다")
    fun `saveAll은 빈 리스트를 저장하면 빈 리스트를 반환한다`() {
        // Act
        val saved = characterEquipmentRepository.saveAll(emptyList())

        // Assert
        assertThat(saved).isEmpty()
    }

    @Test
    @DisplayName("deleteById: 캐릭터 ID로 장비를 삭제한다")
    fun `deleteById는 장비를 삭제한다`() {
        // Arrange
        val characterId = CharacterId.of("test-ocid-007")
        val equipment = createTestEquipment(characterId, """{"weapon": "axe"}""")
        characterEquipmentRepository.save(equipment)
        flushAndClear()

        // 삭제 전 존재 확인
        val beforeDelete = characterEquipmentRepository.findById(characterId)
        assertThat(beforeDelete).isNotNull

        // Act
        characterEquipmentRepository.deleteById(characterId)
        flushAndClear()

        // Assert
        val afterDelete = characterEquipmentRepository.findById(characterId)
        assertThat(afterDelete).isNull()
    }

    @Test
    @DisplayName("deleteById: 존재하지 않는 ID로 삭제해도 예외가 발생하지 않는다")
    fun `deleteById는 존재하지 않는 ID로 삭제해도 예외가 발생하지 않는다`() {
        // Arrange
        val nonExistentId = CharacterId.of("non-existent-ocid")

        // Act & Assert - 예외가 발생하지 않아야 함
        characterEquipmentRepository.deleteById(nonExistentId)
        flushAndClear()

        // 확인
        val found = characterEquipmentRepository.findById(nonExistentId)
        assertThat(found).isNull()
    }

    @Test
    @DisplayName("existsById: 존재하는 캐릭터 ID에 대해 true를 반환한다")
    fun `existsById는 존재하는 캐릭터 ID에 대해 true를 반환한다`() {
        // Arrange
        val characterId = CharacterId.of("test-ocid-008")
        val equipment = createTestEquipment(characterId, """{"weapon": "dagger"}""")
        characterEquipmentRepository.save(equipment)
        flushAndClear()

        // Act
        val exists = characterEquipmentRepository.existsById(characterId)

        // Assert
        assertThat(exists).isTrue
    }

    @Test
    @DisplayName("existsById: 존재하지 않는 캐릭터 ID에 대해 false를 반환한다")
    fun `existsById는 존재하지 않는 캐릭터 ID에 대해 false를 반환한다`() {
        // Arrange
        val nonExistentId = CharacterId.of("non-existent-ocid")

        // Act
        val exists = characterEquipmentRepository.existsById(nonExistentId)

        // Assert
        assertThat(exists).isFalse
    }

    @Test
    @DisplayName("도메인 메서드: 장비 데이터 신선성을 확인할 수 있다")
    fun `장비 데이터의 신선성을 확인할 수 있다`() {
        // Arrange
        val characterId = CharacterId.of("test-ocid-009")
        val equipment = createTestEquipment(characterId, """{"weapon": "spear"}""")
        val saved = characterEquipmentRepository.save(equipment)
        flushAndClear()

        // Act & Assert
        assertThat(saved.isFresh(Duration.ofHours(1))).isTrue
        assertThat(saved.isStale(Duration.ofHours(24))).isTrue
        assertThat(saved.hasData()).isTrue
        assertThat(saved.isEmpty()).isFalse
    }

    @Test
    @DisplayName("도메인 메서드: withUpdatedData로 장비 데이터를 업데이트한다")
    fun `withUpdatedData로 장비 데이터를 업데이트한다`() {
        // Arrange
        val characterId = CharacterId.of("test-ocid-010")
        val equipment = createTestEquipment(characterId, """{"weapon": "old"}""")
        val saved = characterEquipmentRepository.save(equipment)
        flushAndClear()

        val originalUpdatedAt = saved.updatedAt

        // Act
        val updated = saved.withUpdatedData("""{"weapon": "new"}""")
        characterEquipmentRepository.save(updated)
        flushAndClear()

        // Assert
        val found = characterEquipmentRepository.findById(characterId)
        assertThat(found).isNotNull
        assertThat(found!!.jsonContent()).isEqualTo("""{"weapon": "new"}""")
        assertThat(found.updatedAt).isAfter(originalUpdatedAt)
    }

    // ==================== Helper Methods ====================

    /**
     * 테스트용 CharacterEquipment 생성
     */
    private fun createTestEquipment(
        characterId: CharacterId,
        jsonContent: String,
        updatedAt: LocalDateTime = LocalDateTime.now(),
    ): CharacterEquipment = CharacterEquipment.restore(
        characterId = characterId,
        equipmentData = EquipmentData.of(jsonContent),
        updatedAt = updatedAt,
    )
}
