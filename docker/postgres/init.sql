-- PostgreSQL + PGMQ Initialization Script
-- PostgreSQL Migration Issue #547
--
-- NOTE: PGMQ extension requires a custom PostgreSQL build with PGMQ compiled in.
-- For production, use: temboio/tembo or pgmq/pgmq:latest
-- For local development, this init.sql provides a template for manual queue creation.
--
-- To manually create PGMQ-like queue tables (simplified version):
-- In production, these tables will be created by the PGMQ extension.

-- Create queue tables (manual approach for development)
-- These mimic PGMQ's queue structure

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
