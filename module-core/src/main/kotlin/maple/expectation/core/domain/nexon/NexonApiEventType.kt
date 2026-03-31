package maple.expectation.core.domain.nexon

/**
 * Nexon API 이벤트 타입 (Phase 0-3: NexonApiOutbox Entity에서 core로 추출)
 *
 * <p>Nexon API Outbox/PGMQ 메시지의 이벤트 분류.
 * Entity 독립적인 core 도메인으로 이관하여 PGMQ 마이그레이션 시 Entity 삭제 가능.
 *
 * @see maple.expectation.domain.v2.NexonApiOutbox 기존 Entity (Phase 3에서 삭제 예정)
 */
enum class NexonApiEventType {
    GET_OCID,
    GET_CHARACTER_BASIC,
    GET_ITEM_DATA,
    GET_CUBES,
}
