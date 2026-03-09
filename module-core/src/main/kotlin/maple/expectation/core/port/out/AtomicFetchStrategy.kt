package maple.expectation.core.port.out

/**
 * 원자적 Fetch 전략 인터페이스 (LikeSync)
 *
 * <h3>역할</h3>
 *
 * <p>Redis에서 원자적으로 데이터를 가져오고 삭제하는 전략을 정의합니다.
 *
 * <h3>구현체</h3>
 *
 * <ul>
 *   <li>LuaScriptAtomicFetchStrategy: Lua Script 기반 (권장)
 *   <li>RenameAtomicFetchStrategy: RENAME 기반 (폴백)
 * </ul>
 *
 * <h3>설정</h3>
 *
 * <pre>
 * like.sync.strategy: lua | rename
 * </pre>
 */
interface AtomicFetchStrategy {

    /**
     * 원자적으로 키의 모든 필드를 가져오고 삭제
     *
     * @param key Redis 키
     * @return 필드-값 맵 (삭제된 데이터)
     */
    fun fetchAndDelete(key: String): MutableMap<String, String>

    /**
     * 전략 타입 반환
     *
     * @return 전략 타입
     */
    fun getStrategyType(): StrategyType

    enum class StrategyType {
        LUA_SCRIPT,
        RENAME,
    }
}
