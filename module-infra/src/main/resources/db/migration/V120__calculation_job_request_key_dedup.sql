-- V120: request_key based active calculation job dedup.
--
-- The active job insert is the leader claim. COMPLETED/FAILED rows are excluded
-- so future recalculations can create a new active job.

ALTER TABLE calculation_jobs
    ADD COLUMN IF NOT EXISTS request_key VARCHAR(160);

UPDATE calculation_jobs
SET request_key = 'calc:v1:ign:' || lower(btrim(user_ign)) || ':preset:' || preset_no || ':schema:1'
WHERE request_key IS NULL;

WITH ranked AS (
    SELECT
        job_id,
        row_number() OVER (
            PARTITION BY request_key
            ORDER BY created_at DESC, updated_at DESC, job_id DESC
        ) AS rn
    FROM calculation_jobs
    WHERE status IN ('REQUESTED', 'OCID_RESOLVING', 'API_REQUESTED', 'SNAPSHOT_READY', 'CALCULATING', 'RETRYING')
)
UPDATE calculation_jobs cj
SET
    status = 'FAILED',
    last_error_code = 'DUPLICATE_ACTIVE_REQUEST_KEY',
    error_message = 'Superseded during request_key dedup migration',
    updated_at = now()
FROM ranked r
WHERE cj.job_id = r.job_id
  AND r.rn > 1;

CREATE UNIQUE INDEX IF NOT EXISTS ux_calc_jobs_active_request_key
    ON calculation_jobs (request_key)
    WHERE status IN ('REQUESTED', 'OCID_RESOLVING', 'API_REQUESTED', 'SNAPSHOT_READY', 'CALCULATING', 'RETRYING');

CREATE INDEX IF NOT EXISTS idx_calc_jobs_request_key
    ON calculation_jobs (request_key);
