-- ============================================
-- Nexon Raw Data Schema (ADR-006)
-- PostgreSQL-based PGMQ Integration Pipeline
-- ============================================

-- Note: This table uses JSONB-like storage with MySQL's JSON type
-- The table stores raw Nexon API responses for audit trail and reprocessing

CREATE TABLE IF NOT EXISTS nexon_raw_data (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    ocid VARCHAR(100) NOT NULL,
    raw_jsonb JSON NOT NULL COMMENT 'Raw Nexon API response data',
    collected_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    status ENUM('PENDING', 'PROCESSED', 'FAILED') NOT NULL DEFAULT 'PENDING',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    -- Composite index for OCID + status queries
    INDEX idx_nexon_raw_ocid_status (ocid, status),
    -- Index for time-based queries (retention policy)
    INDEX idx_nexon_raw_collected_at (collected_at),
    -- Index for status-based queries
    INDEX idx_nexon_raw_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='Raw Nexon API data for PGMQ-based collection pipeline (ADR-006)';
