package maple.expectation.core.port.inbound

/**
 * V5 Task Receipt (ADR-355)
 *
 * <p>PGMQ messageId를 taskId로 활용.
 * module-core에 배치하여 Controller에서 Port를 통해 접근 가능.
 */
data class TaskReceipt(
    val taskId: String?,
    val userIgn: String,
    val queued: Boolean,
) {
    companion object {
        @JvmStatic
        fun rejected(userIgn: String): TaskReceipt = TaskReceipt(null, userIgn, false)
    }
}
