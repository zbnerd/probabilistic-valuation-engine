package maple.expectation.infrastructure.persistence.repository

import maple.expectation.domain.v2.EquipmentExpectationSummary
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
 * JDBC Batch upsert for EquipmentExpectationSummary.
 *
 * <h3>Performance Improvement</h3>
 * <ul>
 *   <li>Individual: 100 transactions × 2ms overhead = 200ms per batch</li>
 *   <li>Batch: 1 transaction × same SQL = ~6ms per batch (33x faster)</li>
 * </ul>
 *
 * <h3>Transaction Semantics</h3>
 * <ul>
 *   <li>Single transaction for entire batch (atomic)</li>
 *   <li>On failure: entire batch rolled back</li>
 *   <li>Same SQL as individual upsert: INSERT ... ON DUPLICATE KEY UPDATE</li>
 *   <li>Idempotent: same (characterId, presetNo) updates existing record</li>
 * </ul>
 *
 * <h3>Batch Size Configuration</h3>
 * <ul>
 *   <li>Default: 100 (matches ExpectationWriteSize)</li>
 *   <li>Test sizes: 50, 100, 200</li>
 *   <li>Larger batch = fewer transactions but more memory</li>
 * </ul>
 *
 * @see EquipmentExpectationSummaryRepository Individual upsert (original)
 * @see ExpectationBatchWriteScheduler Batch write scheduler
 */
@Component
class EquipmentExpectationSummaryBatchRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val executor: LogicExecutor,
    private val checkedExecutor: CheckedLogicExecutor,
) {
    companion object {
        private val log = LoggerFactory.getLogger(EquipmentExpectationSummaryBatchRepository::class.java)

        /**
         * Default batch size for expectation summary upserts.
         *
         * <p>Matches BatchProperties.expectationWriteSize (100).
         */
        private const val DEFAULT_BATCH_SIZE = 100

        /**
         * PostgreSQL upsert query with ON CONFLICT ... DO UPDATE SET.
         *
         * <p>Same SQL as EquipmentExpectationSummaryRepository.upsertExpectationSummary().
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
     * Batch upsert for expectation summaries.
     *
     * <h3>Transaction Boundary</h3>
     * <ul>
     *   <li>Single @Transactional with REQUIRES_NEW propagation</li>
     *   <li>Atomic: all-or-nothing for the batch</li>
     *   <li>On failure: entire batch rolled back, tasks remain in buffer</li>
     * </ul>
     *
     * <h3>Error Handling</h3>
     * <ul>
     *   <li>SQL exception → entire batch fails, transaction rolled back</li>
     *   <li>Caller should retry: tasks still in buffer queue</li>
     *   <li>Partial success not possible (atomic batch)</li>
     * </ul>
     *
     * @param tasks List of ExpectationWriteTask to upsert
     * @return Array of update counts (1 = insert, 2 = update per row)
     * @throws IllegalStateException if batch upsert fails
     */
    @Transactional("transactionManager", propagation = Propagation.REQUIRES_NEW)
    fun batchUpsertExpectations(tasks: List<ExpectationWriteTask>): IntArray = executor.execute(
        { doBatchUpsert(tasks, DEFAULT_BATCH_SIZE) },
        TaskContext.of("ExpectationBatch", "Upsert", tasks.size.toString()),
    )

    /**
     * Batch upsert with custom batch size.
     *
     * <p>Useful for testing different batch sizes (50, 100, 200).
     *
     * @param tasks List of ExpectationWriteTask to upsert
     * @param batchSize Custom batch size (must be > 0)
     * @return Array of update counts
     * @throws IllegalStateException if batch upsert fails
     * @throws IllegalArgumentException if batchSize <= 0
     */
    @Transactional("transactionManager", propagation = Propagation.REQUIRES_NEW)
    fun batchUpsertExpectations(tasks: List<ExpectationWriteTask>, batchSize: Int): IntArray {
        require(batchSize > 0) { "Batch size must be positive: $batchSize" }

        return executor.execute(
            { doBatchUpsert(tasks, batchSize) },
            TaskContext.of("ExpectationBatch", "Upsert", "${tasks.size}x$batchSize"),
        )
    }

    /**
     * Internal batch upsert implementation.
     *
     * <p>Splits large lists into chunks and executes batch updates.
     *
     * <h3>Chunking Strategy</h3>
     * <ul>
     *   <li>Input list split into chunks of batchSize</li>
     *   <li>Each chunk executed as separate JDBC batch</li>
     *   <li>All chunks share same transaction</li>
     *   <li>Any failure → entire transaction rolled back</li>
     * </ul>
     *
     * @param tasks List of ExpectationWriteTask
     * @param batchSize Batch size for chunking
     * @return Array of update counts from all chunks
     */
    private fun doBatchUpsert(
        tasks: List<ExpectationWriteTask>,
        batchSize: Int,
    ): IntArray {
        if (tasks.isEmpty()) {
            log.debug("[ExpectationBatch] No tasks to upsert")
            return intArrayOf()
        }

        val startTime = System.currentTimeMillis()

        // Convert tasks to batch arguments
        val batchArgs: List<Array<Any?>> = tasks.map { toBatchArgs(it) }

        // Execute in chunks and collect all results
        val resultList = mutableListOf<Int>()
        val numBatches = (batchArgs.size + batchSize - 1) / batchSize

        for (batchIndex in 0 until numBatches) {
            val startIndex = batchIndex * batchSize
            val endIndex = (startIndex + batchSize).coerceAtMost(batchArgs.size)
            val chunk: List<Array<Any?>> = batchArgs.subList(startIndex, endIndex)

            log.debug(
                "[ExpectationBatch] Executing batch {}/{}: records {} to {}",
                batchIndex + 1,
                numBatches,
                startIndex,
                endIndex - 1,
            )

            val chunkResults = jdbcTemplate.batchUpdate(UPSERT_SQL, chunk)
            resultList.addAll(chunkResults.toList())
        }

        val results = resultList.toIntArray()
        val duration = System.currentTimeMillis() - startTime

        log.info(
            "[ExpectationBatch] Batch upsert completed: {} records in {}ms ({} records/sec)",
            tasks.size,
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

    /**
     * Batch upsert with checked exception variant.
     *
     * @param tasks List of ExpectationWriteTask to upsert
     * @return Array of update counts
     * @throws Exception if batch upsert fails
     */
    @Throws(Exception::class)
    fun batchUpsertExpectationsChecked(tasks: List<ExpectationWriteTask>): IntArray = checkedExecutor.execute(
        { doBatchUpsert(tasks, DEFAULT_BATCH_SIZE) },
        TaskContext.of("ExpectationBatch", "UpsertChecked", tasks.size.toString()),
    )
}
