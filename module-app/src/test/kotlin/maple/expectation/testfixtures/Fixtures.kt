package maple.expectation.testfixtures

import java.time.Instant
import maple.expectation.domain.nexon.NexonApiCharacterData
import maple.expectation.infrastructure.external.dto.v2.CharacterListResponse
import maple.expectation.infrastructure.external.dto.v2.EquipmentResponse
import maple.expectation.infrastructure.external.dto.v2.TotalExpectationResponse
import maple.expectation.infrastructure.security.jwt.JwtPayload
import maple.expectation.web.dto.CubeCalculationInput

/**
 * Java 테스트에서 Kotlin data class를 쉽게 생성하기 위한 픽스처
 *
 * Java에서 Kotlin data class의 setter를 사용할 수 없으므,
 * 이 픽스처를 통해 기본값이 채워진 객체를 생성한다.
 */
object Fixtures {

    // ==================== EquipmentResponse.ItemEquipment ====================

    @JvmStatic
    @JvmOverloads
    fun itemEquipment(
        itemEquipmentPart: String? = null,
        itemEquipmentSlot: String? = null,
        itemName: String? = "TestEquipment",
        itemIcon: String? = null,
        itemDescription: String? = null,
        itemShapeName: String? = null,
        itemShapeIcon: String? = null,
        itemGender: String? = null,
        totalOption: EquipmentResponse.ItemOption? = null,
        baseOption: EquipmentResponse.ItemOption? = null,
        addOption: EquipmentResponse.ItemOption? = null,
        etcOption: EquipmentResponse.ItemOption? = null,
        starforceOption: EquipmentResponse.ItemOption? = null,
        exceptionalOption: EquipmentResponse.ItemOption? = null,
        potentialOptionGrade: String? = null,
        potentialOption1: String? = null,
        potentialOption2: String? = null,
        potentialOption3: String? = null,
        additionalPotentialOptionGrade: String? = null,
        additionalPotentialOption1: String? = null,
        additionalPotentialOption2: String? = null,
        additionalPotentialOption3: String? = null,
        equipmentLevelIncrease: String? = null,
        growthExp: String? = null,
        growthLevel: String? = null,
        scrollUpgrade: String? = null,
        cuttableCount: String? = null,
        goldenHammerFlag: String? = null,
        scrollResilienceCount: String? = null,
        scrollUpgradeableCount: String? = null,
        soulName: String? = null,
        soulOption: String? = null,
        starforce: String? = null,
        starforceScrollFlag: String? = null,
        specialRingLevel: String? = null,
        dateExpire: String? = null,
    ): EquipmentResponse.ItemEquipment = EquipmentResponse.ItemEquipment(
        itemEquipmentPart = itemEquipmentPart,
        itemEquipmentSlot = itemEquipmentSlot,
        itemName = itemName,
        itemIcon = itemIcon,
        itemDescription = itemDescription,
        itemShapeName = itemShapeName,
        itemShapeIcon = itemShapeIcon,
        itemGender = itemGender,
        totalOption = totalOption,
        baseOption = baseOption,
        addOption = addOption,
        etcOption = etcOption,
        starforceOption = starforceOption,
        exceptionalOption = exceptionalOption,
        potentialOptionGrade = potentialOptionGrade,
        potentialOption1 = potentialOption1,
        potentialOption2 = potentialOption2,
        potentialOption3 = potentialOption3,
        additionalPotentialOptionGrade = additionalPotentialOptionGrade,
        additionalPotentialOption1 = additionalPotentialOption1,
        additionalPotentialOption2 = additionalPotentialOption2,
        additionalPotentialOption3 = additionalPotentialOption3,
        equipmentLevelIncrease = equipmentLevelIncrease,
        growthExp = growthExp,
        growthLevel = growthLevel,
        scrollUpgrade = scrollUpgrade,
        cuttableCount = cuttableCount,
        goldenHammerFlag = goldenHammerFlag,
        scrollResilienceCount = scrollResilienceCount,
        scrollUpgradeableCount = scrollUpgradeableCount,
        soulName = soulName,
        soulOption = soulOption,
        starforce = starforce,
        starforceScrollFlag = starforceScrollFlag,
        specialRingLevel = specialRingLevel,
        dateExpire = dateExpire,
    )

    // ==================== EquipmentResponse ====================

    @JvmStatic
    @JvmOverloads
    fun equipmentResponse(
        date: String? = null,
        characterGender: String? = null,
        characterClass: String? = null,
        presetNo: Int? = null,
        itemEquipment: List<EquipmentResponse.ItemEquipment>? = null,
        itemEquipmentPreset1: List<EquipmentResponse.ItemEquipment>? = null,
        itemEquipmentPreset2: List<EquipmentResponse.ItemEquipment>? = null,
        itemEquipmentPreset3: List<EquipmentResponse.ItemEquipment>? = null,
        dragonEquipment: List<EquipmentResponse.ItemEquipment>? = null,
        mechanicEquipment: List<EquipmentResponse.ItemEquipment>? = null,
        title: EquipmentResponse.Title? = null,
    ): EquipmentResponse = EquipmentResponse(
        date = date,
        characterGender = characterGender,
        characterClass = characterClass,
        presetNo = presetNo,
        itemEquipment = itemEquipment,
        itemEquipmentPreset1 = itemEquipmentPreset1,
        itemEquipmentPreset2 = itemEquipmentPreset2,
        itemEquipmentPreset3 = itemEquipmentPreset3,
        dragonEquipment = dragonEquipment,
        mechanicEquipment = mechanicEquipment,
        title = title,
    )

    // ==================== JwtPayload ====================

    @JvmStatic
    fun jwtPayload(sessionId: String, fingerprint: String, role: String, ttlSeconds: Long): JwtPayload = JwtPayload.of(
        sessionId = sessionId,
        fingerprint = fingerprint,
        role = role,
        ttlSeconds = ttlSeconds,
    )

    // ==================== CubeCalculationInput ====================

    @JvmStatic
    @JvmOverloads
    fun cubeCalculationInput(
        itemName: String? = "TestEquipment",
        level: Int = 0,
        part: String? = null,
        grade: String? = null,
        options: List<String> = emptyList(),
    ): CubeCalculationInput = CubeCalculationInput(
        itemName = itemName,
        level = level,
        part = part,
        grade = grade,
        options = options.toMutableList(),
    )

    // ==================== CharacterListResponse.CharacterInfo ====================

    @JvmStatic
    @JvmOverloads
    fun characterInfo(ocid: String? = null, characterName: String? = null): CharacterListResponse.CharacterInfo = CharacterListResponse.CharacterInfo(
        ocid = ocid,
        characterName = characterName,
    )

    // ==================== TotalExpectationResponse ====================

    @JvmStatic
    @JvmOverloads
    fun totalExpectationResponse(
        userIgn: String? = null,
        totalCost: Long = 0,
        totalCostText: String? = null,
        items: List<TotalExpectationResponse.ItemExpectation>? = null,
    ): TotalExpectationResponse = TotalExpectationResponse(
        userIgn = userIgn,
        totalCost = totalCost,
        totalCostText = totalCostText,
        items = items,
    )

    @JvmStatic
    @JvmOverloads
    fun itemExpectation(
        part: String? = null,
        itemName: String? = null,
        potential: String? = null,
        expectedCost: Long = 0,
        expectedCostText: String? = null,
        expectedCount: Long = 0,
    ): TotalExpectationResponse.ItemExpectation = TotalExpectationResponse.ItemExpectation(
        part = part,
        itemName = itemName,
        potential = potential,
        expectedCost = expectedCost,
        expectedCostText = expectedCostText,
        expectedCount = expectedCount,
    )

    // ==================== NexonApiCharacterData ====================

    @JvmStatic
    @JvmOverloads
    fun nexonApiCharacterData(
        id: Long? = null,
        ocid: String? = null,
        characterName: String? = null,
        worldName: String? = null,
        characterClass: String? = null,
        characterLevel: Int? = null,
        guildName: String? = null,
        characterImageUrl: String? = null,
        date: Instant? = null,
    ): NexonApiCharacterData = NexonApiCharacterData(
        id = id,
        ocid = ocid,
        characterName = characterName,
        worldName = worldName,
        characterClass = characterClass,
        characterLevel = characterLevel,
        guildName = guildName,
        characterImageUrl = characterImageUrl,
        date = date,
    )
}
