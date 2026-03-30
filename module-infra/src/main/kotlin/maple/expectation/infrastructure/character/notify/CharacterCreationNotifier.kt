package maple.expectation.infrastructure.character.notify

import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Character Creation Event Notifier
 *
 * <p>PostgreSQL NOTIFY를 사용하여 캐릭터 생성 이벤트를 발행합니다.
 *
 * <h3>Channel</h3>
 * character_creation:{userIgn}
 *
 * <h3>Usage</h3>
 * CharacterCreationService에서 캐릭터 저장 후 호출하여 이벤트 발행
 *
 * @see CharacterCreationListener
 */
@Component
class CharacterCreationNotifier(
    private val jdbcTemplate: JdbcTemplate,
    private val executor: LogicExecutor,
) {
    companion object {
        private val log = LoggerFactory.getLogger(CharacterCreationNotifier::class.java)
        private const val CHANNEL_PREFIX = "character_creation"
    }

    /**
     * 캐릭터 생성 이벤트 발행
     *
     * <p>NOTIFY는 비동기로 전송되며, 수신자가 없어도 에러가 발생하지 않음.
     *
     * @param userIgn 캐릭터 닉네임
     */
    fun notifyCharacterCreated(userIgn: String) {
        val context = TaskContext.of("CharacterCreation", "Notify", userIgn)
        val channel = "$CHANNEL_PREFIX:$userIgn"

        executor.executeVoid({
            try {
                // PostgreSQL NOTIFY with userIgn as payload
                jdbcTemplate.execute("NOTIFY \"$channel\"")
                log.debug("[CharacterCreation] Notified: {}", userIgn)
            } catch (e: Exception) {
                log.warn("[CharacterCreation] Notify failed for: {}", userIgn, e)
            }
        }, context)
    }
}
