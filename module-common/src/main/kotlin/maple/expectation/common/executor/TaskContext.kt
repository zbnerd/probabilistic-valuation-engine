package maple.expectation.common.executor

/**
 * Task execution context for metrics and logging.
 *
 * <p>Controls metric cardinality by structuring task names.
 *
 * ## Format
 * ```
 * "component:operation:dynamicValue"
 *
 * Examples:
 * - TaskContext.of("V5Query", "CacheFirstLookup", "userIgn")
 *   → "V5Query:CacheFirstLookup:userIgn"
 * - TaskContext.of("Observability", "track")
 *   → "Observability:track"
 * ```
 *
 * ## P1 Policy: Metric Cardinality Control
 * - component, operation: used as metric tags (fixed values)
 * - dynamicValue: logged only (excluded from metrics)
 *
 * @property component Component name (e.g., "V5Query", "Observability")
 * @property operation Operation type (e.g., "track", "execute")
 * @property dynamicValue Optional dynamic value (e.g., method signature, parameter)
 */
data class TaskContext(
    val component: String,
    val operation: String,
    val dynamicValue: String? = null,
) {
    private val normalizedDynamicValue: String = dynamicValue ?: ""

    init {
        require(component.isNotBlank()) { "component must not be blank" }
        require(operation.isNotBlank()) { "operation must not be blank" }
    }

    fun component(): String = component
    fun operation(): String = operation
    fun dynamicValue(): String = normalizedDynamicValue

    /**
     * Convert to task name string.
     *
     * @return "component:operation:dynamicValue" format
     */
    fun toTaskName(): String = if (normalizedDynamicValue.isEmpty()) {
        "$component:$operation"
    } else {
        "$component:$operation:$normalizedDynamicValue"
    }

    companion object {
        /**
         * Create TaskContext without dynamic value.
         */
        @JvmStatic
        fun of(component: String, operation: String): TaskContext = TaskContext(component, operation, null)

        /**
         * Create TaskContext with dynamic value.
         */
        @JvmStatic
        fun of(component: String, operation: String, dynamicValue: String?): TaskContext = TaskContext(component, operation, dynamicValue)
    }
}
