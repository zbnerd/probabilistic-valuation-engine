-- ============================================
-- Event Outbox Schema (Issue #490)
-- Generic Multi-Stream Event Outbox Pattern
-- ============================================

-- Event Outbox Table
CREATE TABLE IF NOT EXISTS event_outbox (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    version BIGINT DEFAULT 0,
    target_stream VARCHAR(100) NOT NULL COMMENT 'Redis stream name (e.g., character-sync, guild-sync)',
    event_type VARCHAR(50) NOT NULL COMMENT 'Event type for routing',
    payload TEXT NOT NULL COMMENT 'JSON payload',
    content_hash VARCHAR(64) NOT NULL COMMENT 'SHA-256 hash for integrity verification',
    status ENUM('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'DEAD_LETTER')
           NOT NULL DEFAULT 'PENDING',
    locked_by VARCHAR(100) NULL COMMENT 'Instance ID that locked this record',
    locked_at DATETIME NULL COMMENT 'When the record was locked',
    retry_count INT NOT NULL DEFAULT 0 COMMENT 'Number of retry attempts',
    max_retries INT NOT NULL DEFAULT 3 COMMENT 'Maximum retry attempts before DLQ',
    last_error VARCHAR(500) NULL COMMENT 'Last error message',
    next_retry_at DATETIME NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Next retry timestamp',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Creation timestamp',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'Last update timestamp',

    -- Polling optimization index for pending messages (status + next_retry_at + id for SKIP LOCKED)
    INDEX idx_event_pending_poll (status, next_retry_at, id),
    -- Index for lock management (skip locked optimization)
    INDEX idx_event_locked (locked_by, locked_at),
    -- Index for multi-stream routing (target_stream + status)
    INDEX idx_event_target_stream (target_stream, status),
    -- Index for stale event detection by updated timestamp
    INDEX idx_event_updated_at (status, updated_at),
    -- Index for cleanup operations
    INDEX idx_event_cleanup (status, updated_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='Generic Event Outbox for Multi-Stream Redis Integration';

-- Optional: Event DLQ Table (Dead Letter Queue)
CREATE TABLE IF NOT EXISTS event_dlq (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    original_outbox_id BIGINT NOT NULL COMMENT 'Original outbox record ID',
    target_stream VARCHAR(100) NOT NULL COMMENT 'Original target stream',
    event_type VARCHAR(50) NOT NULL COMMENT 'Original event type',
    payload TEXT NOT NULL COMMENT 'Original payload',
    failure_reason VARCHAR(500) NULL COMMENT 'Reason for DLQ placement',
    retry_count INT NOT NULL DEFAULT 0 COMMENT 'Number of retry attempts before DLQ',
    moved_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'When moved to DLQ',

    INDEX idx_event_dlq_moved_at (moved_at),
    INDEX idx_event_dlq_target_stream (target_stream),
    INDEX idx_event_dlq_event_type (event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='Dead Letter Queue for Failed Event Outbox Messages';
