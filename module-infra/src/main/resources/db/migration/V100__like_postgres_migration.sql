-- V100__like_postgres_migration.sql
-- Like System PostgreSQL Migration
-- Replaces Redis buffer with UNLOGGED table for performance

-- 1. UNLOGGED table for high-performance like buffering
-- Survives server restarts but truncated on crash (acceptable for buffer)
CREATE UNLOGGED TABLE IF NOT EXISTS character_like_buffer (
    id BIGSERIAL PRIMARY KEY,
    character_name VARCHAR(13) NOT NULL,
    user_id BIGINT NOT NULL,
    delta INTEGER NOT NULL DEFAULT 1,
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    processed BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_like_buffer UNIQUE (character_name, user_id, requested_at)
);

-- Index for efficient pending sync queries
CREATE INDEX IF NOT EXISTS idx_like_buffer_pending
ON character_like_buffer(character_name, processed)
WHERE processed = false;

-- Index for user-based queries
CREATE INDEX IF NOT EXISTS idx_like_buffer_user
ON character_like_buffer(user_id, requested_at DESC);

-- 2. Main like count table (regular, durable)
CREATE TABLE IF NOT EXISTS character_like_count (
    character_name VARCHAR(13) PRIMARY KEY,
    count BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- 3. Like relation table (who liked whom)
CREATE TABLE IF NOT EXISTS character_like_relation (
    id BIGSERIAL PRIMARY KEY,
    character_name VARCHAR(13) NOT NULL,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_like_relation UNIQUE (character_name, user_id)
);

-- Index for character's likers
CREATE INDEX IF NOT EXISTS idx_like_relation_character
ON character_like_relation(character_name, created_at DESC);

-- Index for user's likes
CREATE INDEX IF NOT EXISTS idx_like_relation_user
ON character_like_relation(user_id, created_at DESC);

-- 4. PGMQ queue for like sync (if not exists)
-- Note: PGMQ queues are created via SELECT pgmq.create('queue_name');
-- This will be done in application startup or init-pgmq.sql

-- Comments
COMMENT ON TABLE character_like_buffer IS 'UNLOGGED buffer for high-performance like writes (PostgreSQL L2 replacement)';
COMMENT ON TABLE character_like_count IS 'Durable like count storage';
COMMENT ON TABLE character_like_relation IS 'Like relations (who liked whom)';
