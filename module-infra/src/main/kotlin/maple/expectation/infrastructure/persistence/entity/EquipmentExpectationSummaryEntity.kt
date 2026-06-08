package maple.expectation.infrastructure.persistence.entity

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * 장비 기대값 요약 엔티티 (#240)
 *
 * <h3>5-Agent Council 합의사항</h3>
 *
 * <ul>
 *   <li>🟣 Purple (Auditor): BigDecimal 필수 - 정밀 계산
 *   <li>🔴 Red (SRE): InnoDB Buffer Pool 오염 방지 - JSON 대신 계산된 값만 저장
 *   <li>🟢 Green (Performance): 인덱스 최적화 - game_character_id로 빠른 조회
 * </ul>
 *
 * <h3>DB 스키마</h3>
 *
 * <pre>
 * CREATE TABLE equipment_expectation_summary (
 *     id BIGINT AUTO_INCREMENT PRIMARY KEY,
 *     game_character_id BIGINT NOT NULL,
 *     preset_no INT NOT NULL DEFAULT 1,
 *     total_expected_cost DECIMAL(20, 2) NOT NULL,
 *     black_cube_cost DECIMAL(20, 2) NOT NULL DEFAULT 0,
 *     red_cube_cost DECIMAL(20, 2) NOT NULL DEFAULT 0,
 *     additional_cube_cost DECIMAL(20, 2) NOT NULL DEFAULT 0,
 *     starforce_cost DECIMAL(20, 2) NOT NULL DEFAULT 0,
 *     calculated_at DATETIME NOT NULL,
 *     version BIGINT DEFAULT 0,
 *     INDEX idx_game_character_preset (game_character_id, preset_no),
 *     UNIQUE KEY uk_character_preset (game_character_id, preset_no)
 * );
 * </pre>
 *
 * @see GameCharacter 연관 엔티티
 */
@Entity
@Table(
    name = "equipment_expectation_summary",
    indexes = [
        Index(name = "idx_game_character_preset", columnList = "game_character_id, preset_no"),
    ],
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_character_preset",
            columnNames = ["game_character_id", "preset_no"],
        ),
    ],
)
class EquipmentExpectationSummaryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    /**
     * 연관된 게임 캐릭터 ID
     *
     * <p>FK는 어플리케이션 레벨에서 관리 (성능상 이유로 물리 FK 미사용)
     */
    @Column(name = "game_character_id", nullable = false)
    var gameCharacterId: Long? = null

    /**
     * 프리셋 번호 (1, 2, 3)
     *
     * <p>메이플스토리는 프리셋별로 장비 구성이 다름
     */
    @Column(name = "preset_no", nullable = false)
    var presetNo: Int? = null

    /**
     * 총 기대 비용 (모든 강화 합산)
     *
     * <p>DECIMAL(20, 2): 최대 999경 메소까지 표현 가능
     */
    @Column(name = "total_expected_cost", nullable = false, precision = 20, scale = 2)
    var totalExpectedCost: BigDecimal? = null

    /** 블랙큐브 기대 비용 (윗잠재) */
    @Column(name = "black_cube_cost", nullable = false, precision = 20, scale = 2)
    var blackCubeCost: BigDecimal? = null

    /** 레드큐브 기대 비용 (윗잠재 보조) */
    @Column(name = "red_cube_cost", nullable = false, precision = 20, scale = 2)
    var redCubeCost: BigDecimal? = null

    /** 에디셔널큐브 기대 비용 (아랫잠재) */
    @Column(name = "additional_cube_cost", nullable = false, precision = 20, scale = 2)
    var additionalCubeCost: BigDecimal? = null

    /** 스타포스 기대 비용 */
    @Column(name = "starforce_cost", nullable = false, precision = 20, scale = 2)
    var starforceCost: BigDecimal? = null

    /** 계산 시점 */
    @Column(name = "calculated_at", nullable = false)
    var calculatedAt: LocalDateTime? = null

    /** 낙관적 락 버전 (동시성 제어) */
    @Version
    var version: Long? = null

    private constructor()

    private constructor(
        gameCharacterId: Long?,
        presetNo: Int?,
        totalExpectedCost: BigDecimal?,
        blackCubeCost: BigDecimal?,
        redCubeCost: BigDecimal?,
        additionalCubeCost: BigDecimal?,
        starforceCost: BigDecimal?,
    ) {
        this.gameCharacterId = gameCharacterId
        this.presetNo = presetNo ?: 1
        this.totalExpectedCost = totalExpectedCost ?: BigDecimal.ZERO
        this.blackCubeCost = blackCubeCost ?: BigDecimal.ZERO
        this.redCubeCost = redCubeCost ?: BigDecimal.ZERO
        this.additionalCubeCost = additionalCubeCost ?: BigDecimal.ZERO
        this.starforceCost = starforceCost ?: BigDecimal.ZERO
        this.calculatedAt = LocalDateTime.now()
    }

    companion object {
        @JvmStatic
        fun create(
            gameCharacterId: Long?,
            presetNo: Int?,
            totalExpectedCost: BigDecimal?,
            blackCubeCost: BigDecimal?,
            redCubeCost: BigDecimal?,
            additionalCubeCost: BigDecimal?,
            starforceCost: BigDecimal?,
        ): EquipmentExpectationSummaryEntity = EquipmentExpectationSummaryEntity(
            gameCharacterId,
            presetNo,
            totalExpectedCost,
            blackCubeCost,
            redCubeCost,
            additionalCubeCost,
            starforceCost,
        )
    }
}
