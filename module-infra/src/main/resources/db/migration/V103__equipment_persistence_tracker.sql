-- Equipment Persistence Tracker: Regular table for crash recovery
-- Tracks in-progress async equipment save operations across instance failures
CREATE TABLE IF NOT EXISTS equipment_persistence_tracker (
    ocid VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    instance_id VARCHAR(50),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP
);

-- Primary key
ALTER TABLE equipment_persistence_tracker ADD CONSTRAINT pk_ept_ocid PRIMARY KEY (ocid);

-- Partial index for pending operations recovery
CREATE INDEX IF NOT EXISTS idx_ept_pending ON equipment_persistence_tracker(status) WHERE status = 'PENDING';
