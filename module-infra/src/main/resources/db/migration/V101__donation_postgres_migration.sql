-- V101__donation_postgres_migration.sql
-- Donation System PostgreSQL Migration
-- MySQL → PostgreSQL syntax conversion for outbox pattern

-- 1. Main donation outbox table with optimistic locking
CREATE TABLE IF NOT EXISTS donation_outbox (
    id BIGSERIAL PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    request_id VARCHAR(50) NOT NULL UNIQUE,
    event_type VARCHAR(50) NOT NULL,
    payload TEXT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    locked_by VARCHAR(100),
    locked_at TIMESTAMP WITH TIME ZONE,
    retry_count INTEGER NOT NULL DEFAULT 0,
    max_retries INTEGER NOT NULL DEFAULT 3,
    last_error VARCHAR(500),
    next_retry_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Indexes for outbox processing (SKIP LOCKED optimization)
CREATE INDEX IF NOT EXISTS idx_outbox_pending_poll
ON donation_outbox(status, next_retry_at, id)
WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_outbox_locked
ON donation_outbox(locked_by, locked_at)
WHERE locked_by IS NOT NULL;

-- 2. Dead Letter Queue for failed donations
CREATE TABLE IF NOT EXISTS donation_dlq (
    id BIGSERIAL PRIMARY KEY,
    original_outbox_id BIGINT NOT NULL,
    request_id VARCHAR(50) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    payload TEXT NOT NULL,
    failure_reason VARCHAR(500),
    moved_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_dlq_moved_at
ON donation_dlq(moved_at DESC);

-- Comments for documentation
COMMENT ON TABLE donation_outbox IS 'Transactional outbox for donation events with optimistic locking via version column';
COMMENT ON TABLE donation_dlq IS 'Dead letter queue for unrecoverable donation failures (Triple Safety Net layer 1)';
COMMENT ON COLUMN donation_outbox.version IS 'Optimistic locking version counter';
COMMENT ON COLUMN donation_outbox.locked_by IS 'Claim ID for SKIP LOCKED processing';
COMMENT ON COLUMN donation_outbox.next_retry_at IS 'Scheduled retry timestamp for exponential backoff';
