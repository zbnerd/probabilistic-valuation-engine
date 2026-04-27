-- V116: Fix active-job dedup index to cover NULL OCID rows
-- The previous index excluded rows where ocid IS NULL, removing the DB-level
-- guard for concurrent requests creating duplicate active jobs.

DROP INDEX IF EXISTS idx_calc_jobs_active_dedup;
CREATE UNIQUE INDEX idx_calc_jobs_active_dedup
    ON calculation_jobs (user_ign, preset_no)
    WHERE status IN ('REQUESTED', 'OCID_RESOLVING', 'OCID_RESOLVED', 'API_REQUESTED', 'SNAPSHOT_READY', 'CALCULATING', 'RETRYING');
