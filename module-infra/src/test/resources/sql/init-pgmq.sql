-- PGMQ Extension Initialization
-- This script runs when the PostgreSQL container starts

-- Create PGMQ extension
CREATE EXTENSION IF NOT EXISTS pgmq CASCADE;

-- Create application queues
SELECT pgmq.create('calculation_queue');
SELECT pgmq.create('v4_buffer_queue');
SELECT pgmq.create('v5_event_queue');
SELECT pgmq.create('donation_outbox_queue');
