-- Hot Key Counter (UNLOGGED for performance)
-- ADR-005: Single Flight + Hot Key Strategy
--
-- This table tracks cache key access frequency to detect hot keys (>100 RPS)
-- UNLOGGED table provides high write performance (no WAL overhead)
-- Data is lost on crash but that's acceptable for transient metrics

CREATE UNLOGGED TABLE IF NOT EXISTS hot_key_counter (
    key VARCHAR(255) NOT NULL,
    count BIGINT NOT NULL DEFAULT 1,
    window_start TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (key, window_start)
);

-- Index for efficient time-window queries
CREATE INDEX IF NOT EXISTS idx_hot_key_window ON hot_key_counter(window_start);

-- Comment
COMMENT ON TABLE hot_key_counter IS 'Hot key detection counter (UNLOGGED for performance)';
COMMENT ON COLUMN hot_key_counter.key IS 'Cache key being tracked';
COMMENT ON COLUMN hot_key_counter.count IS 'Access count in this time window';
COMMENT ON COLUMN hot_key_counter.window_start IS 'Window start timestamp (seconds precision)';
