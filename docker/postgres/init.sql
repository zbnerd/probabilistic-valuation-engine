-- PostgreSQL + PGMQ Initialization Script
-- PostgreSQL Migration Issue #547

-- Create PGMQ extension (requires pg_partman and pgcrypto)
CREATE EXTENSION IF NOT EXISTS pgmq;

-- Create queues for async processing (replacing Redis queues)
-- V4 Buffer Queue
SELECT pgmq.create('v4_buffer_queue');

-- V5 Event Queue
SELECT pgmq.create('v5_event_queue');

-- Donation Outbox Queue
SELECT pgmq.create('donation_outbox_queue');

-- Grant permissions
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO maple;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO maple;

-- Log initialization
DO $$
BEGIN
    RAISE NOTICE 'PostgreSQL + PGMQ initialized successfully';
END $$;
