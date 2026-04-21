package maple.expectation.core.dto.cube

import maple.expectation.core.domain.stat.StatType

/**
 * 큐브 기대값 계산 입력 DTO
 *
 * 두 가지 모드를 지원합니다:
 * - **기존 모드**: options 리스트로 정확한 옵션 조합 지정
 * - **DP 모드**: targetStatType + minTotal로 "21% 이상" 같은 누적 확률 계산
 *
 * #240 V4 확장:
 * - 에디셔널 잠재능력 (additionalGrade, additionalOptions)
 * - 스타포스 정보 (starforce, starforceScrollFlag)
 * - 아이콘 URL (itemIcon)
 * - 장비 세부 분류 (itemEquipmentPart)
 */
data class CubeCalculationInput(
    var level: Int = 0,
    // 장비 레벨 (숫자)
    var part: String? = null,
    // 장비 부위 (item_equipment_slot)
    var grade: String? = null,
    // 잠재능력 등급
    var expectedCost: Long = 0,
    var options: MutableList<String?> = ArrayList(),
    // 옵션 3줄 리스트 (기존 방식, null 허용)
    var itemName: String? = null,
    // ========== #240 V4 확장 필드 ==========

    /** 아이템 아이콘 URL (예: "https://open.api.nexon.com/static/maplestory/...") */
    var itemIcon: String? = null,

    /** 장비 세부 분류 (보조무기 분류용, 예: "포스실드", "소울링", "모자") */
    var itemEquipmentPart: String? = null,

    /** 에디셔널 잠재능력 등급 (예: "레어", "에픽", "유니크", "레전드리") */
    var additionalGrade: String? = null,

    /** 에디셔널 잠재능력 옵션 3줄 */
    var additionalOptions: MutableList<String> = ArrayList(),

    /** 현재 스타포스 수치 (0~25) */
    var starforce: Int = 0,

    /** 놀장(스타포스 스크롤) 사용 여부 (Nexon API: "사용" / "미사용") */
    var starforceScrollFlag: String? = null,

    /** 특수 스킬 반지 레벨 (리스트레인트링, 컨티뉴어스링 등, 0이면 일반 장비, 1~5이면 특수 스킬 반지) */
    var specialRingLevel: Int = 0,
    // ========== 환생의 불꽃 필드 (#303 동적 계산) ==========

    /** 추옵 STR */
    var addOptionStr: Int = 0,

    /** 추옵 DEX */
    var addOptionDex: Int = 0,

    /** 추옵 INT */
    var addOptionInt: Int = 0,

    /** 추옵 LUK */
    var addOptionLuk: Int = 0,

    /** 추옵 최대 HP */
    var addOptionMaxHp: Int = 0,

    /** 추옵 올스탯% */
    var addOptionAllStat: Int = 0,

    /** 추옵 공격력 */
    var addOptionAtt: Int = 0,

    /** 추옵 마력 */
    var addOptionMag: Int = 0,

    /** 추옵 보스 데미지% */
    var addOptionBossDmg: Int = 0,

    /** 추옵 데미지% */
    var addOptionDmg: Int = 0,

    /** 기본 공격력 (item_base_option.attack_power) */
    var baseAttackPower: Int = 0,

    /** 기본 마력 (item_base_option.magic_power) */
    var baseMagicPower: Int = 0,
    // ========== DP 모드용 필드 (신규) ==========

    /** 목표 스탯 타입 (단위 포함) 예: STR_PERCENT, DEX_PERCENT, ALLSTAT_PERCENT */
    var targetStatType: StatType? = null,

    /** 목표 합계 (%) 예: 21 → "STR 21% 이상" 조건 */
    var minTotal: Int? = null,

    /** Tail Clamp 활성화 여부 (true: 상태공간 O(target) 보장, false: 전체 상태공간 계산) */
    @get:JvmName("isEnableTailClamp")
    var enableTailClamp: Boolean = true,

    /** 확률 테이블 버전 (감사/재현성용, 서비스에서 자동 설정됨) */
    var probabilityTableVersion: String? = null,
) {
    /** 놀장 장비 여부 판별 */
    fun isNoljangEquipment(): Boolean = "사용" == starforceScrollFlag

    /** DP 모드 여부 판별 (targetStatType과 minTotal이 모두 설정되면 true) */
    fun isDpMode(): Boolean = targetStatType != null && minTotal != null

    /**
     * DP 모드 필수 필드 검증 (침묵 실패 방지)
     * P0: UNKNOWN 타입 거부 추가 (Fail-Fast)
     *
     * @throws IllegalArgumentException 필수 필드 누락 또는 무효 시
     */
    fun validateForDpMode() {
        require(isDpMode()) { "DP 모드 필수: targetStatType, minTotal" }
        // P0: UNKNOWN 타입 거부 (Fail-Fast)
        require(targetStatType != StatType.UNKNOWN) { "DP 모드 무효: targetStatType=UNKNOWN" }
        require(part != null && grade != null && level > 0) { "분포 조회 필수: part, grade, level" }
        // P0: minTotal 범위 검증 (>=0 허용, 상한 제거)
        // target=0은 수학적으로 잘 정의됨 (항상 성공 = 확률 1.0)
        // 상한 제거: HP%, 보공 등 미래 확장 대응
        val total = requireNotNull(minTotal) { "minTotal은 DP 모드 필수입니다" }
        require(total >= 0) { "minTotal은 음수일 수 없습니다: $total" }
    }

    /** 기존 방식 유효성 검사 (옵션이 3줄 다 모였는지 등) */
    fun isReady(): Boolean {
        // 1. 필수 필드 체크 (부위, 등급)
        if (part == null || grade == null) {
            return false
        }

        // 2. 옵션 개수 체크
        if (options.size != 3) {
            return false
        }

        // 3. 옵션 내용 체크: 전부 null이거나 빈 문자열이면 계산 불가
        return options.any { opt ->
            opt?.trim()?.isNotEmpty() == true && !"null".equals(opt, ignoreCase = true)
        }
    }

    /**
     * 기본 정보 존재 여부 확인 (화면 표시용)
     * 잠재능력이 없는 장비(특수스킬반지 등)도 화면에 표시하기 위해 part(슬롯)만 있으면 true를 반환합니다.
     *
     * @return part가 있으면 true
     */
    fun hasBasicInfo(): Boolean {
        val partValue = part ?: return false
        return partValue.trim().isNotEmpty()
    }

    /**
     * No-arg constructor for Jackson deserialization and Java frameworks
     */
    constructor() : this(
        level = 0,
        part = null,
        grade = null,
        expectedCost = 0,
        options = ArrayList(),
        itemName = null,
        itemIcon = null,
        itemEquipmentPart = null,
        additionalGrade = null,
        additionalOptions = ArrayList(),
        starforce = 0,
        starforceScrollFlag = null,
        specialRingLevel = 0,
        addOptionStr = 0,
        addOptionDex = 0,
        addOptionInt = 0,
        addOptionLuk = 0,
        addOptionMaxHp = 0,
        addOptionAllStat = 0,
        addOptionAtt = 0,
        addOptionMag = 0,
        addOptionBossDmg = 0,
        addOptionDmg = 0,
        baseAttackPower = 0,
        baseMagicPower = 0,
        targetStatType = null,
        minTotal = null,
        enableTailClamp = true,
        probabilityTableVersion = null,
    )

    companion object {
        @JvmStatic
        fun builder(): CubeCalculationInputBuilder = CubeCalculationInputBuilder()
    }
}

/**
 * Java Builder 패턴 호환용 빌더 클래스
 * Kotlin data class는 기본적으로 copy()를 제공하지만,
 * Java 코드에서 사용하는 builder() 패턴과의 호환성을 위해 제공
 */
class CubeCalculationInputBuilder {
    private var level: Int = 0
    private var part: String? = null
    private var grade: String? = null
    private var expectedCost: Long = 0
    private var options: MutableList<String?> = ArrayList()
    private var itemName: String? = null
    private var itemIcon: String? = null
    private var itemEquipmentPart: String? = null
    private var additionalGrade: String? = null
    private var additionalOptions: MutableList<String> = ArrayList()
    private var starforce: Int = 0
    private var starforceScrollFlag: String? = null
    private var specialRingLevel: Int = 0
    private var addOptionStr: Int = 0
    private var addOptionDex: Int = 0
    private var addOptionInt: Int = 0
    private var addOptionLuk: Int = 0
    private var addOptionMaxHp: Int = 0
    private var addOptionAllStat: Int = 0
    private var addOptionAtt: Int = 0
    private var addOptionMag: Int = 0
    private var addOptionBossDmg: Int = 0
    private var addOptionDmg: Int = 0
    private var baseAttackPower: Int = 0
    private var baseMagicPower: Int = 0
    private var targetStatType: StatType? = null
    private var minTotal: Int? = null
    private var enableTailClamp: Boolean = true
    private var probabilityTableVersion: String? = null

    fun level(level: Int) = apply { this.level = level }
    fun part(part: String?) = apply { this.part = part }
    fun grade(grade: String?) = apply { this.grade = grade }
    fun expectedCost(expectedCost: Long) = apply { this.expectedCost = expectedCost }
    fun options(options: MutableList<String?>) = apply { this.options = options }
    fun itemName(itemName: String?) = apply { this.itemName = itemName }
    fun itemIcon(itemIcon: String?) = apply { this.itemIcon = itemIcon }
    fun itemEquipmentPart(itemEquipmentPart: String?) = apply { this.itemEquipmentPart = itemEquipmentPart }
    fun additionalGrade(additionalGrade: String?) = apply { this.additionalGrade = additionalGrade }
    fun additionalOptions(additionalOptions: MutableList<String>) = apply { this.additionalOptions = additionalOptions }
    fun starforce(starforce: Int) = apply { this.starforce = starforce }
    fun starforceScrollFlag(starforceScrollFlag: String?) = apply { this.starforceScrollFlag = starforceScrollFlag }
    fun specialRingLevel(specialRingLevel: Int) = apply { this.specialRingLevel = specialRingLevel }
    fun addOptionStr(addOptionStr: Int) = apply { this.addOptionStr = addOptionStr }
    fun addOptionDex(addOptionDex: Int) = apply { this.addOptionDex = addOptionDex }
    fun addOptionInt(addOptionInt: Int) = apply { this.addOptionInt = addOptionInt }
    fun addOptionLuk(addOptionLuk: Int) = apply { this.addOptionLuk = addOptionLuk }
    fun addOptionMaxHp(addOptionMaxHp: Int) = apply { this.addOptionMaxHp = addOptionMaxHp }
    fun addOptionAllStat(addOptionAllStat: Int) = apply { this.addOptionAllStat = addOptionAllStat }
    fun addOptionAtt(addOptionAtt: Int) = apply { this.addOptionAtt = addOptionAtt }
    fun addOptionMag(addOptionMag: Int) = apply { this.addOptionMag = addOptionMag }
    fun addOptionBossDmg(addOptionBossDmg: Int) = apply { this.addOptionBossDmg = addOptionBossDmg }
    fun addOptionDmg(addOptionDmg: Int) = apply { this.addOptionDmg = addOptionDmg }
    fun baseAttackPower(baseAttackPower: Int) = apply { this.baseAttackPower = baseAttackPower }
    fun baseMagicPower(baseMagicPower: Int) = apply { this.baseMagicPower = baseMagicPower }
    fun targetStatType(targetStatType: StatType?) = apply { this.targetStatType = targetStatType }
    fun minTotal(minTotal: Int?) = apply { this.minTotal = minTotal }
    fun enableTailClamp(enableTailClamp: Boolean) = apply { this.enableTailClamp = enableTailClamp }
    fun probabilityTableVersion(probabilityTableVersion: String?) = apply {
        this.probabilityTableVersion =
            probabilityTableVersion
    }

    fun build(): CubeCalculationInput = CubeCalculationInput(
        level = level,
        part = part,
        grade = grade,
        expectedCost = expectedCost,
        options = options,
        itemName = itemName,
        itemIcon = itemIcon,
        itemEquipmentPart = itemEquipmentPart,
        additionalGrade = additionalGrade,
        additionalOptions = additionalOptions,
        starforce = starforce,
        starforceScrollFlag = starforceScrollFlag,
        specialRingLevel = specialRingLevel,
        addOptionStr = addOptionStr,
        addOptionDex = addOptionDex,
        addOptionInt = addOptionInt,
        addOptionLuk = addOptionLuk,
        addOptionMaxHp = addOptionMaxHp,
        addOptionAllStat = addOptionAllStat,
        addOptionAtt = addOptionAtt,
        addOptionMag = addOptionMag,
        addOptionBossDmg = addOptionBossDmg,
        addOptionDmg = addOptionDmg,
        baseAttackPower = baseAttackPower,
        baseMagicPower = baseMagicPower,
        targetStatType = targetStatType,
        minTotal = minTotal,
        enableTailClamp = enableTailClamp,
        probabilityTableVersion = probabilityTableVersion,
    )
}
