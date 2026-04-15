-- DLQ replay 추적 메타데이터 테이블 (#646)
-- PGMQ archive 테이블에 의존하지 않고 애플리케이션 스키마에 생성
-- (pgmq 스키마에 생성 시 PGMQ 업그레이드 호환성 위험)

CREATE TABLE IF NOT EXISTS dlq_replay_meta (
    queue_name    TEXT NOT NULL,
    message_id    BIGINT NOT NULL,
    replay_count  INT DEFAULT 0,
    first_failed_at TIMESTAMPTZ DEFAULT NOW(),
    last_replayed_at TIMESTAMPTZ,
    PRIMARY KEY (queue_name, message_id)
);

CREATE INDEX idx_dlq_replay_candidates
    ON dlq_replay_meta (queue_name, replay_count, last_replayed_at)
    WHERE replay_count < 3;

COMMENT ON TABLE dlq_replay_meta IS 'DLQ replay tracking metadata. Prevents infinite replay loops.';
