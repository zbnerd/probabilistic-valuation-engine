-- PostgreSQL + PGMQ Initialization Script
-- PostgreSQL Migration Issue #547
--
-- Docker Image: jumski/postgres-17-pgmq:latest (PostgreSQL 17 + PGMQ 1.5.1)
-- Alternative: any PostgreSQL image with PGMQ extension compiled in

-- Create PGMQ extension (requires pg_partman and pgcrypto)
CREATE EXTENSION IF NOT EXISTS pgmq CASCADE;

-- Create queues for async processing (replacing Redis queues)
-- V4 Buffer Queue
SELECT pgmq.create('v4_buffer_queue');

-- V5 Event Queue
SELECT pgmq.create('v5_event_queue');

-- Donation Outbox Queue
SELECT pgmq.create('donation_outbox_queue');

-- Grant permissions on pgmq schema
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA pgmq TO maple;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA pgmq TO maple;
GRANT ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA pgmq TO maple;

-- Log initialization
DO $$
BEGIN
    RAISE NOTICE 'PostgreSQL + PGMQ initialized successfully';
END $$;
