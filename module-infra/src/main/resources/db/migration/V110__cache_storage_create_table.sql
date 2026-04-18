-- V110__cache_storage_create_table.sql
-- Issue #715: Create cache_storage table (missing from V102, V107)
-- PostgreSQL L2 cache tier for TieredCache (ADR-022)

CREATE UNLOGGED TABLE IF NOT EXISTS cache_storage (
    cache_key VARCHAR(500) PRIMARY KEY,
    cache_value BYTEA NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);

COMMENT ON TABLE cache_storage IS 'PostgreSQL L2 cache tier (UNLOGGED). Key format: {cacheName}:v1:{actualKey}';
