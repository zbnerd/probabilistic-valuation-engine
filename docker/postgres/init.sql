-- PostgreSQL + PGMQ Initialization Script
-- MapleExpectation PostgreSQL Migration (Issue #547)
--
-- Docker Image: pgmq/pgmq:latest (PostgreSQL 17 + PGMQ)
-- Alternative: any PostgreSQL image with PGMQ extension compiled in

-- Create PGMQ extension (requires pg_partman and pgcrypto)
CREATE EXTENSION IF NOT EXISTS pgmq CASCADE;

-- Create PGMQ Queues
-- V4 Buffer Queue: Equipment expectation write-back buffer
SELECT pgmq.create('v4_buffer_queue');

-- V5 Event Queue: Domain event stream
SELECT pgmq.create('v5_event_queue');

-- Donation Outbox Queue: Donation transaction outbox
SELECT pgmq.create('donation_outbox_queue');

-- Nexon API Retry Queue: Failed API call retry via PGMQ (Phase 3)
SELECT pgmq.create('nexon_retry_queue');

-- Create UNLOGGED Tables for high-performance buffers
-- These tables survive server restarts but are truncated on crash

-- Equipment Expectation Buffer (replaces Redis buffer)
CREATE UNLOGGED TABLE IF NOT EXISTS equipment_expectation_buffer (
    character_name VARCHAR(50) PRIMARY KEY,
    expectation_value BIGINT NOT NULL,
    calculated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

-- Like Count Buffer (replaces Redis like buffer)
CREATE UNLOGGED TABLE IF NOT EXISTS character_like_buffer (
    character_name VARCHAR(50) PRIMARY KEY,
    like_count BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Session Storage (replaces Redis session)
CREATE TABLE IF NOT EXISTS user_session (
    session_id VARCHAR(128) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    character_name VARCHAR(50),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_user_session_user_id ON user_session(user_id);
CREATE INDEX IF NOT EXISTS idx_user_session_expires_at ON user_session(expires_at);

-- Refresh Token Storage (replaces Redis refresh token)
CREATE TABLE IF NOT EXISTS refresh_token (
    token_id VARCHAR(128) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token_hash VARCHAR(256) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_refresh_token_user_id ON refresh_token(user_id);
CREATE INDEX IF NOT EXISTS idx_refresh_token_expires_at ON refresh_token(expires_at);

-- Equipment Data (jsonb for flexible schema)
CREATE TABLE IF NOT EXISTS equipment_data (
    character_name VARCHAR(50) PRIMARY KEY,
    equipment_json JSONB NOT NULL,
    fetched_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0
);

-- Create indexes for JSONB queries
CREATE INDEX IF NOT EXISTS idx_equipment_data_jsonb ON equipment_data USING GIN (equipment_json);

-- Grant permissions on public schema
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO maple;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO maple;

-- Grant permissions on pgmq schema
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA pgmq TO maple;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA pgmq TO maple;
GRANT ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA pgmq TO maple;

-- Log initialization
DO $$
BEGIN
    RAISE NOTICE 'PostgreSQL + PGMQ initialized successfully';
    RAISE NOTICE 'Created queues: v4_buffer_queue, v5_event_queue, donation_outbox_queue';
END $$;
