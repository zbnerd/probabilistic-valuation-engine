package maple.expectation.core.policy

import java.util.EnumMap
import java.util.NavigableMap
import java.util.TreeMap
import maple.expectation.core.domain.model.CubeType
import maple.expectation.core.domain.model.PotentialGrade

/**
 * Table-based cost calculation strategy (default implementation).
 *
 * <p>Responsibilities (Single Responsibility Principle):
 * - Lookup cost from static table
 * - O(1) lookup performance using EnumMap
 *
 * <p>OCP Compliance:
 * - Extension: Implements {@link CostCalculationStrategy} interface
 * - Modification prevention: New cube types added without code changes
 */
class TableBasedCostStrategy : CostCalculationStrategy {

    private val costMasterTable: Map<CubeType, NavigableMap<Int, EnumMap<PotentialGrade, Long>>>

    init {
        this.costMasterTable = initializeCostTable()
    }

    override fun calculateCost(type: CubeType, level: Int, grade: String): Long {
        // Fail-Fast: Immediate exception on invalid input (prevent Silent Failure)
        val validGrade = PotentialGrade.fromKorean(grade)

        val typeTable = costMasterTable[type]
            ?: throw IllegalStateException("Unknown CubeType: $type")

        // Find closest lower bracket key for level (e.g., level 210 -> key 200)
        val levelKey = typeTable.floorKey(level) ?: typeTable.firstKey()

        // O(1) EnumMap lookup
        return typeTable[levelKey]!![validGrade]!!
    }

    /**
     * Initialize cost table (called from constructor).
     *
     * @return Cost master table
     */
    private fun initializeCostTable(): Map<CubeType, NavigableMap<Int, EnumMap<PotentialGrade, Long>>> {
        val table = mutableMapOf<CubeType, NavigableMap<Int, EnumMap<PotentialGrade, Long>>>()

        // 1. Black Cube (upper potential) cost table
        val blackTable: NavigableMap<Int, EnumMap<PotentialGrade, Long>> = TreeMap<Int, EnumMap<PotentialGrade, Long>>().apply {
            put(0, createGradeMap(4_000_000L, 16_000_000L, 34_000_000L, 40_000_000L))
            put(160, createGradeMap(4_250_000L, 17_000_000L, 36_125_000L, 42_500_000L))
            put(200, createGradeMap(4_500_000L, 18_000_000L, 38_250_000L, 45_000_000L))
            put(250, createGradeMap(5_000_000L, 20_000_000L, 42_500_000L, 50_000_000L))
        }
        table[CubeType.BLACK] = blackTable

        // 2. Additional Cube (lower potential) cost table
        val addiTable: NavigableMap<Int, EnumMap<PotentialGrade, Long>> = TreeMap<Int, EnumMap<PotentialGrade, Long>>().apply {
            put(0, createGradeMap(13_000_000L, 36_400_000L, 44_200_000L, 52_000_000L))
            put(160, createGradeMap(13_812_500L, 38_675_000L, 46_962_500L, 55_250_000L))
            put(200, createGradeMap(14_625_000L, 40_950_000L, 49_725_000L, 58_500_000L))
            put(250, createGradeMap(16_250_000L, 45_500_000L, 55_250_000L, 65_000_000L))
        }
        table[CubeType.ADDITIONAL] = addiTable

        // 3. Red Cube - no level distinction, returns 1 to treat as 'count'
        val redTable: NavigableMap<Int, EnumMap<PotentialGrade, Long>> = TreeMap<Int, EnumMap<PotentialGrade, Long>>().apply {
            put(0, createGradeMap(1L, 1L, 1L, 1L))
        }
        table[CubeType.RED] = redTable

        return table
    }

    /**
     * Create EnumMap helper - maps costs in RARE, EPIC, UNIQUE, LEGENDARY order.
     */
    private fun createGradeMap(
        rare: Long,
        epic: Long,
        unique: Long,
        legendary: Long,
    ): EnumMap<PotentialGrade, Long> {
        val map = EnumMap<PotentialGrade, Long>(PotentialGrade::class.java)
        map[PotentialGrade.RARE] = rare
        map[PotentialGrade.EPIC] = epic
        map[PotentialGrade.UNIQUE] = unique
        map[PotentialGrade.LEGENDARY] = legendary
        return map
    }
}
