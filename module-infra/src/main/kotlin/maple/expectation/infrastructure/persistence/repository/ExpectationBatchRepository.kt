package maple.expectation.infrastructure.persistence.repository

import maple.expectation.infrastructure.buffer.ExpectationWriteTask
import maple.expectation.infrastructure.executor.CheckedLogicExecutor
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Batch Repository for Expectation Write Tasks (Issue #617 US-003)
 *
 * <h3>Purpose</h3>
 * Provides batch upsert capability for ExpectationWriteTask using PostgreSQL ON CONFLICT.
 *
 * <h3>Performance</h3>
 * <ul>
 *   <li>Batch upsert: Single transaction for multiple records</li>
 *   <li>PostgreSQL ON CONFLICT DO UPDATE for atomic upsert</li>
 *   <li>JdbcTemplate.batchUpdate for efficient batch execution</li>
 * </ul>
 *
 * @param jdbcTemplate JDBC template for batch operations
 * @param executor Logic executor for execution with metrics
 * @param checkedExecutor Checked executor for operations with checked exceptions
 */
@Component
class ExpectationBatchRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val executor: LogicExecutor,
    private val checkedExecutor: CheckedLogicExecutor,
) {
    companion object {
        private val log = LoggerFactory.getLogger(ExpectationBatchRepository::class.java)
        private const val BATCH_SIZE = 100

        /**
         * PostgreSQL upsert query with ON CONFLICT ... DO UPDATE SET.
         *
         * <p>Unique Key: (game_character_id, preset_no)
         */
        private val UPSERT_SQL = """
            INSERT INTO equipment_expectation_summary
                (game_character_id, preset_no, total_expected_cost, black_cube_cost,
                 red_cube_cost, additional_cube_cost, starforce_cost, calculated_at, version)
            VALUES
                (?, ?, ?, ?, ?, ?, ?, NOW(), 0)
            ON CONFLICT (game_character_id, preset_no) DO UPDATE SET
                total_expected_cost = EXCLUDED.total_expected_cost,
                black_cube_cost = EXCLUDED.black_cube_cost,
                red_cube_cost = EXCLUDED.red_cube_cost,
                additional_cube_cost = EXCLUDED.additional_cube_cost,
                starforce_cost = EXCLUDED.starforce_cost,
                calculated_at = NOW()
        """.trimIndent()
    }

    /**
     * Batch upsert for expectation write tasks.
     *
     * <h3>Transaction Boundary</h3>
     * <ul>
     *   <li>Single @Transactional with REQUIRES_NEW propagation</li>
     *   <li>Atomic: all-or-nothing for the batch</li>
     *   <li>On failure: entire batch rolled back</li>
     * </ul>
     *
     * @param tasks List of ExpectationWriteTask to upsert
     * @return Array of update counts (1 = insert, 2 = update per row)
     * @throws IllegalStateException if batch upsert fails
     */
    @Transactional("transactionManager", propagation = Propagation.REQUIRES_NEW)
    fun batchUpsert(tasks: List<ExpectationWriteTask>): IntArray = executor.execute(
        { doBatchUpsert(tasks) },
        TaskContext.of("ExpectationBatchRepo", "BatchUpsert", tasks.size.toString()),
    )

    /**
     * Batch upsert with checked exception variant.
     *
     * @param tasks List of ExpectationWriteTask to upsert
     * @return Array of update counts
     * @throws Exception if batch upsert fails
     */
    @Throws(Exception::class)
    fun batchUpsertChecked(tasks: List<ExpectationWriteTask>): IntArray = checkedExecutor.execute(
        { doBatchUpsert(tasks) },
        TaskContext.of("ExpectationBatchRepo", "BatchUpsertChecked", tasks.size.toString()),
    )

    /**
     * Internal batch upsert implementation.
     *
     * @param tasks List of ExpectationWriteTask
     * @return Array of update counts from JDBC batch update
     */
    private fun doBatchUpsert(tasks: List<ExpectationWriteTask>): IntArray {
        if (tasks.isEmpty()) {
            log.debug("[ExpectationBatchRepo] No tasks to upsert")
            return intArrayOf()
        }

        val startTime = System.currentTimeMillis()

        val results = tasks.chunked(BATCH_SIZE).flatMap { chunk ->
            val batchArgs = chunk.map { toBatchArgs(it) }
            jdbcTemplate.batchUpdate(UPSERT_SQL, batchArgs).toList()
        }.toIntArray()

        val duration = System.currentTimeMillis() - startTime

        log.info(
            "[ExpectationBatchRepo] Batch upsert completed: {} records in {} chunks of {} in {}ms ({} records/sec)",
            tasks.size,
            tasks.chunked(BATCH_SIZE).size,
            BATCH_SIZE,
            duration,
            if (duration > 0) tasks.size * 1000L / duration else 0,
        )

        return results
    }

    /**
     * Converts ExpectationWriteTask to JDBC batch arguments.
     *
     * @param task Write task containing character expectation data
     * @return Object array [characterId, presetNo, totalCost, blackCube, redCube, additionalCube, starforceCost]
     */
    private fun toBatchArgs(task: ExpectationWriteTask): Array<Any?> = arrayOf(
        task.characterId,
        task.presetNo,
        task.totalExpectedCost,
        task.blackCubeCost,
        task.redCubeCost,
        task.additionalCubeCost,
        task.starforceCost,
    )
}
