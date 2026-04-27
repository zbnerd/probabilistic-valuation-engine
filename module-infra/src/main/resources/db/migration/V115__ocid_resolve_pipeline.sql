-- ============================================================
-- V115: OCID Resolve Pipeline — nullable ocid + ocid_resolve_queue
-- ============================================================

-- Make ocid nullable for async OCID resolution
ALTER TABLE calculation_jobs ALTER COLUMN ocid DROP NOT NULL;

-- Drop and recreate unique index to allow NULL ocid (partial index excludes NULL)
DROP INDEX IF EXISTS idx_calc_jobs_active_dedup;
CREATE UNIQUE INDEX idx_calc_jobs_active_dedup
    ON calculation_jobs (user_ign, preset_no)
    WHERE status IN ('REQUESTED', 'OCID_RESOLVING', 'API_REQUESTED', 'SNAPSHOT_READY', 'CALCULATING', 'RETRYING')
      AND ocid IS NOT NULL;

-- Add index for OCID_RESOLVING stale detection
CREATE INDEX idx_calc_jobs_ocid_resolving ON calculation_jobs (updated_at)
    WHERE status = 'OCID_RESOLVING';

-- Create OCID resolve queue
SELECT pgmq.create('ocid_resolve_queue');

CREATE INDEX IF NOT EXISTS idx_pgmq_ocid_resolve_job_id
    ON pgmq.q_ocid_resolve_queue ((message ->> 'jobId'));
