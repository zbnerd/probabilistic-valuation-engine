package maple.expectation.core.calculation.probability

import java.util.Collections
import java.util.LinkedHashMap
import maple.expectation.core.calculation.error.MissingProbabilityException
import maple.expectation.core.calculation.error.ProbabilityTableInitializationException

class ProbabilityTableSnapshot(
    val version: ProbabilityTableVersion,
    index: Map<ProbabilityKey, List<ProbabilityRow>>,
) {
    private val index: Map<ProbabilityKey, List<ProbabilityRow>> = immutableValidatedCopy(index)

    val rowCount: Int = this.index.values.sumOf { rows -> rows.size }

    fun rows(key: ProbabilityKey): List<ProbabilityRow> = index[key]
        ?.takeIf { rows -> rows.isNotEmpty() }
        ?: throw MissingProbabilityException(key)

    fun keys(): List<ProbabilityKey> = Collections.unmodifiableList(index.keys.toList())

    fun entries(): List<Pair<ProbabilityKey, ProbabilityRow>> = Collections.unmodifiableList(
        index.flatMap { (key, rows) -> rows.map { row -> key to row } },
    )

    class Builder internal constructor(
        private val version: ProbabilityTableVersion,
    ) {
        private val rowsByKey = LinkedHashMap<ProbabilityKey, MutableList<ProbabilityRow>>()

        fun add(key: ProbabilityKey, row: ProbabilityRow): Builder = apply {
            rowsByKey.computeIfAbsent(key) { mutableListOf() }.add(row)
        }

        fun support(key: ProbabilityKey): Builder = apply {
            rowsByKey.computeIfAbsent(key) { mutableListOf() }
        }

        fun build(): ProbabilityTableSnapshot = ProbabilityTableSnapshot(version, rowsByKey)
    }

    private fun immutableValidatedCopy(
        source: Map<ProbabilityKey, List<ProbabilityRow>>,
    ): Map<ProbabilityKey, List<ProbabilityRow>> {
        val copied = LinkedHashMap<ProbabilityKey, List<ProbabilityRow>>(source.size)
        val ratesByIdentity = HashMap<Pair<ProbabilityKey, String>, Double>()
        source.forEach { (key, rows) ->
            val copiedRows = rows.toList()
            copiedRows.forEach { row ->
                val identity = key to row.optionName
                val previousRate = ratesByIdentity.putIfAbsent(identity, row.rate)
                if (previousRate != null && previousRate != row.rate) {
                    throw ProbabilityTableInitializationException(
                        "Conflicting probability rates for key=$key option=${row.optionName}: $previousRate vs ${row.rate}",
                    )
                }
            }
            copied[key] = Collections.unmodifiableList(copiedRows)
        }
        return Collections.unmodifiableMap(copied)
    }

    companion object {
        fun builder(version: ProbabilityTableVersion): Builder = Builder(version)
    }
}
