-- ============================================
-- Event Outbox Schema (Issue #490)
-- V5 Migration (Issue #589, #590, #591): PostgreSQL-only
-- Generic Multi-Stream Event Outbox Pattern (PGMQ)
-- ============================================

-- Event Outbox Table
CREATE TABLE IF NOT EXISTS event_outbox (
    id BIGSERIAL PRIMARY KEY,
    version BIGINT DEFAULT 0,
    target_stream VARCHAR(100) NOT NULL,  -- PGMQ queue name (e.g., character-sync, guild-sync)
    event_type VARCHAR(50) NOT NULL,
    payload TEXT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'DEAD_LETTER')),
    locked_by VARCHAR(100) NULL,
    locked_at TIMESTAMP NULL,
    retry_count INT NOT NULL DEFAULT 0,
    max_retries INT NOT NULL DEFAULT 3,
    last_error VARCHAR(500) NULL,
    next_retry_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

-- Indexes for polling optimization
CREATE INDEX IF NOT EXISTS idx_event_pending_poll ON event_outbox (status, next_retry_at, id);
CREATE INDEX IF NOT EXISTS idx_event_locked ON event_outbox (locked_by, locked_at);
CREATE INDEX IF NOT EXISTS idx_event_target_stream ON event_outbox (target_stream, status);
CREATE INDEX IF NOT EXISTS idx_event_updated_at ON event_outbox (status, updated_at);
CREATE INDEX IF NOT EXISTS idx_event_cleanup ON event_outbox (status, updated_at, id);

COMMENT ON TABLE event_outbox IS 'Generic Event Outbox for Multi-Stream PGMQ Integration';
COMMENT ON COLUMN event_outbox.target_stream IS 'PGMQ queue name (e.g., character-sync, guild-sync)';
COMMENT ON COLUMN event_outbox.event_type IS 'Event type for routing';
COMMENT ON COLUMN event_outbox.payload IS 'JSON payload';
COMMENT ON COLUMN event_outbox.content_hash IS 'SHA-256 hash for integrity verification';
COMMENT ON COLUMN event_outbox.status IS 'Message status: PENDING, PROCESSING, COMPLETED, FAILED, DEAD_LETTER';
COMMENT ON COLUMN event_outbox.locked_by IS 'Instance ID that locked this record';
COMMENT ON COLUMN event_outbox.locked_at IS 'When the record was locked';
COMMENT ON COLUMN event_outbox.retry_count IS 'Number of retry attempts';
COMMENT ON COLUMN event_outbox.max_retries IS 'Maximum retry attempts before DLQ';
COMMENT ON COLUMN event_outbox.last_error IS 'Last error message';
COMMENT ON COLUMN event_outbox.next_retry_at IS 'Next retry timestamp';

-- Optional: Event DLQ Table (Dead Letter Queue)
CREATE TABLE IF NOT EXISTS event_dlq (
    id BIGSERIAL PRIMARY KEY,
    original_outbox_id BIGINT NOT NULL,
    target_stream VARCHAR(100) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    payload TEXT NOT NULL,
    failure_reason VARCHAR(500) NULL,
    retry_count INT NOT NULL DEFAULT 0,
    moved_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE INDEX IF NOT EXISTS idx_event_dlq_moved_at ON event_dlq (moved_at);
CREATE INDEX IF NOT EXISTS idx_event_dlq_target_stream ON event_dlq (target_stream);
CREATE INDEX IF NOT EXISTS idx_event_dlq_event_type ON event_dlq (event_type);

COMMENT ON TABLE event_dlq IS 'Dead Letter Queue for Failed Event Outbox Messages';
