CREATE TABLE IF NOT EXISTS chunk_execution (
    id BIGSERIAL PRIMARY KEY,

    execution_type TEXT NOT NULL,
    run_id TEXT NOT NULL,
    endpoint TEXT NOT NULL,
    chunk_id TEXT NOT NULL,

    topic TEXT NOT NULL,
    message_key TEXT NOT NULL,
    event_type TEXT NOT NULL,
    schema_version INT NOT NULL,
    event_payload_jsonb JSONB NOT NULL,

    status TEXT NOT NULL,

    attempt_count INT NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ NULL,
    first_failed_at TIMESTAMPTZ NULL,
    last_failed_at TIMESTAMPTZ NULL,
    last_error TEXT NULL,
    terminal_reason TEXT NULL,

    processing_started_at TIMESTAMPTZ NULL,
    lease_until TIMESTAMPTZ NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_chunk_execution_identity
        UNIQUE (execution_type, run_id, endpoint, chunk_id),
    CONSTRAINT chk_chunk_execution_status CHECK (
        status IN (
            'PENDING',
            'PROCESSING',
            'FAILED_RETRYABLE',
            'FAILED_TERMINAL',
            'SUCCEEDED'
        )
    )
);

CREATE INDEX IF NOT EXISTS idx_chunk_execution_retry
ON chunk_execution (status, next_retry_at)
WHERE status = 'FAILED_RETRYABLE';

CREATE INDEX IF NOT EXISTS idx_chunk_execution_processing_lease
ON chunk_execution (status, lease_until)
WHERE status = 'PROCESSING';

CREATE INDEX IF NOT EXISTS idx_chunk_execution_run
ON chunk_execution (run_id, endpoint, chunk_id);
