-- Kafka Transactional Outbox: kafka_outbox_events
-- Kafka Pipeline Transition Plan Section 10
-- Stores Kafka publish intents in the same DB transaction as job creation.
-- KafkaOutboxPublisher polls and publishes to Kafka asynchronously.

CREATE TABLE IF NOT EXISTS kafka_outbox_events (
    id UUID PRIMARY KEY,
    event_type TEXT NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_type TEXT NOT NULL,
    topic TEXT NOT NULL,
    partition_key TEXT NOT NULL,
    payload JSONB NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Prevent duplicate outbox events for the same event type + aggregate
-- Only active statuses are indexed (PENDING, PUBLISHING, PUBLISHED)
CREATE UNIQUE INDEX IF NOT EXISTS ux_kafka_outbox_event_dedup
ON kafka_outbox_events (event_type, aggregate_id)
WHERE status IN ('PENDING', 'PUBLISHING', 'PUBLISHED');

-- Outbox publisher claim query uses this index
CREATE INDEX IF NOT EXISTS ix_kafka_outbox_events_pending
ON kafka_outbox_events (status, next_attempt_at, created_at)
WHERE status = 'PENDING';

COMMENT ON TABLE kafka_outbox_events IS 'Transactional outbox for Kafka publish intents. Populated in same TX as job creation.';
COMMENT ON COLUMN kafka_outbox_events.status IS 'PENDING -> PUBLISHING -> PUBLISHED. Retryable: PENDING with backoff.';
COMMENT ON COLUMN kafka_outbox_events.partition_key IS 'Kafka partition key (requestKey or jobId)';
