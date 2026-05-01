-- V120: request_key based active calculation job dedup.
--
-- The active job insert is the leader claim. COMPLETED/FAILED rows are excluded
-- so future recalculations can create a new active job.

ALTER TABLE calculation_jobs
    ADD COLUMN IF NOT EXISTS request_key VARCHAR(160);

UPDATE calculation_jobs
SET request_key = 'calc:v1:ign:' || lower(btrim(user_ign)) || ':preset:' || preset_no || ':schema:1'
WHERE request_key IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_calc_jobs_active_request_key
    ON calculation_jobs (request_key)
    WHERE status IN ('REQUESTED', 'OCID_RESOLVING', 'API_REQUESTED', 'SNAPSHOT_READY', 'CALCULATING', 'RETRYING');

CREATE INDEX IF NOT EXISTS idx_calc_jobs_request_key
    ON calculation_jobs (request_key);
