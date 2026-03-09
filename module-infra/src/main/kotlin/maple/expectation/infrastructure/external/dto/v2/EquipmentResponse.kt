package maple.expectation.infrastructure.external.dto.v2

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class EquipmentResponse(
    // ==========================================
    // 1️⃣ Level 1: 응답 최상위 (Root)
    // ==========================================
    @JsonProperty("date")
    val date: String? = null,

    @JsonProperty("character_gender")
    val characterGender: String? = null,

    @JsonProperty("character_class")
    val characterClass: String? = null,

    @JsonProperty("preset_no")
    val presetNo: Int? = null,
    // --- 메인 장비 리스트 ---
    @JsonProperty("item_equipment")
    val itemEquipment: List<ItemEquipment>? = null,
    // --- 프리셋 리스트 (1~3) ---
    @JsonProperty("item_equipment_preset_1")
    val itemEquipmentPreset1: List<ItemEquipment>? = null,

    @JsonProperty("item_equipment_preset_2")
    val itemEquipmentPreset2: List<ItemEquipment>? = null,

    @JsonProperty("item_equipment_preset_3")
    val itemEquipmentPreset3: List<ItemEquipment>? = null,
    // --- 특수 장비 (에반, 메카닉 등) ---
    // 일반 장비와 구조가 같으므로 ItemEquipment 재사용
    @JsonProperty("dragon_equipment")
    val dragonEquipment: List<ItemEquipment>? = null,

    @JsonProperty("mechanic_equipment")
    val mechanicEquipment: List<ItemEquipment>? = null,
    // --- 칭호 ---
    @JsonProperty("title")
    val title: Title? = null,
) {

    // ==========================================
    // 2️⃣ Level 2: 아이템 1개 상세 정보 (ItemEquipment)
    // ==========================================
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ItemEquipment(
        @JsonProperty("item_equipment_part")
        val itemEquipmentPart: String? = null,
        // 장착 부위 (모자, 상의 등)

        @JsonProperty("item_equipment_slot")
        val itemEquipmentSlot: String? = null,

        @JsonProperty("item_name")
        val itemName: String? = null,

        @JsonProperty("item_icon")
        val itemIcon: String? = null,

        @JsonProperty("item_description")
        val itemDescription: String? = null,

        @JsonProperty("item_shape_name")
        val itemShapeName: String? = null,

        @JsonProperty("item_shape_icon")
        val itemShapeIcon: String? = null,

        @JsonProperty("item_gender")
        val itemGender: String? = null,
        // --- 📊 핵심: 옵션 정보들 (전부 ItemOption 클래스 재사용) ---
        @JsonProperty("item_total_option")
        val totalOption: ItemOption? = null,
        // 최종 옵션

        @JsonProperty("item_base_option")
        val baseOption: ItemOption? = null,
        // 깡통 옵션

        @JsonProperty("item_add_option")
        val addOption: ItemOption? = null,
        // 추옵

        @JsonProperty("item_etc_option")
        val etcOption: ItemOption? = null,
        // 작(주문서) 상태

        @JsonProperty("item_starforce_option")
        val starforceOption: ItemOption? = null,
        // 스타포스로 오르는 수치

        @JsonProperty("item_exceptional_option")
        val exceptionalOption: ItemOption? = null,
        // 익셉셔널 강화 수치

        // --- ✨ 잠재능력 (윗잠) ---
        @JsonProperty("potential_option_grade")
        val potentialOptionGrade: String? = null,
        // 등급 (레전드리 등)

        @JsonProperty("potential_option_1")
        val potentialOption1: String? = null,

        @JsonProperty("potential_option_2")
        val potentialOption2: String? = null,

        @JsonProperty("potential_option_3")
        val potentialOption3: String? = null,
        // --- ✨ 에디셔널 (아랫잠) ---
        @JsonProperty("additional_potential_option_grade")
        val additionalPotentialOptionGrade: String? = null,

        @JsonProperty("additional_potential_option_1")
        val additionalPotentialOption1: String? = null,

        @JsonProperty("additional_potential_option_2")
        val additionalPotentialOption2: String? = null,

        @JsonProperty("additional_potential_option_3")
        val additionalPotentialOption3: String? = null,
        // --- 기타 강화 정보 ---
        @JsonProperty("equipment_level_increase")
        val equipmentLevelIncrease: String? = null,
        // 착감 등

        @JsonProperty("growth_exp")
        val growthExp: String? = null,

        @JsonProperty("growth_level")
        val growthLevel: String? = null,

        @JsonProperty("scroll_upgrade")
        val scrollUpgrade: String? = null,
        // 업횟

        @JsonProperty("cuttable_count")
        val cuttableCount: String? = null,
        // 가횟

        @JsonProperty("golden_hammer_flag")
        val goldenHammerFlag: String? = null,

        @JsonProperty("scroll_resilience_count")
        val scrollResilienceCount: String? = null,
        // 복구 가능 횟수

        @JsonProperty("scroll_upgradeable_count")
        val scrollUpgradeableCount: String? = null,
        // 황망 등 남은 횟수

        @JsonProperty("soul_name")
        val soulName: String? = null,

        @JsonProperty("soul_option")
        val soulOption: String? = null,

        @JsonProperty("starforce")
        val starforce: String? = null,
        // ★ 스타포스 수치

        @JsonProperty("starforce_scroll_flag")
        val starforceScrollFlag: String? = null,
        // 슈페리얼 등 여부

        @JsonProperty("special_ring_level")
        val specialRingLevel: String? = null,
        // 시드링 레벨

        @JsonProperty("date_expire")
        val dateExpire: String? = null,
    )

    // ==========================================
    // 3️⃣ Level 3: 옵션 수치 상세 (ItemOption)
    // ==========================================
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ItemOption(
        // 안전하게 String으로 받고, 나중에 Integer.parseInt() 사용 권장
        @JsonProperty("str")
        val str: String? = null,

        @JsonProperty("dex")
        val dex: String? = null,

        @JsonProperty("int") // ⚠️ 중요: 자바 예약어 'int'와 충돌 방지
        val intValue: String? = null,

        @JsonProperty("luk")
        val luk: String? = null,

        @JsonProperty("max_hp")
        val maxHp: String? = null,

        @JsonProperty("max_mp")
        val maxMp: String? = null,

        @JsonProperty("attack_power")
        val attackPower: String? = null,

        @JsonProperty("magic_power")
        val magicPower: String? = null,

        @JsonProperty("armor")
        val armor: String? = null,

        @JsonProperty("speed")
        val speed: String? = null,

        @JsonProperty("jump")
        val jump: String? = null,

        @JsonProperty("boss_damage")
        val bossDamage: String? = null,

        @JsonProperty("ignore_monster_armor") // 방무
        val ignoreMonsterArmor: String? = null,

        @JsonProperty("all_stat")
        val allStat: String? = null,
        // 올스탯 %

        @JsonProperty("damage") // 데미지 %
        val damage: String? = null,

        @JsonProperty("equipment_level_decrease") // 착감
        val equipmentLevelDecrease: String? = null,

        @JsonProperty("max_hp_rate")
        val maxHpRate: String? = null,

        @JsonProperty("max_mp_rate")
        val maxMpRate: String? = null,

        @JsonProperty("base_equipment_level") // 기본 옵션에만 존재
        val baseEquipmentLevel: String? = null,

        @JsonProperty("exceptional_upgrade") // 익셉셔널에만 존재 (1강 등)
        val exceptionalUpgrade: String? = null,
    )

    // ==========================================
    // 4️⃣ 번외: 칭호 (Title)
    // ==========================================
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Title(
        @JsonProperty("title_name")
        val titleName: String? = null,

        @JsonProperty("title_icon")
        val titleIcon: String? = null,

        @JsonProperty("title_description")
        val titleDescription: String? = null,

        @JsonProperty("date_expire")
        val dateExpire: String? = null,

        @JsonProperty("date_option_expire")
        val dateOptionExpire: String? = null,
    )
}
