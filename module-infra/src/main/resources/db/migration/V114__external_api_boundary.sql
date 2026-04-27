-- ============================================================
-- V114: External API Boundary — state table, snapshots, queues
-- ============================================================

CREATE TABLE calculation_jobs (
    job_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ocid            VARCHAR(64) NOT NULL,
    user_ign        VARCHAR(64) NOT NULL,
    preset_no       INT DEFAULT 1,
    status          VARCHAR(32) NOT NULL DEFAULT 'REQUESTED',
    snapshot_id     UUID,
    retry_count     INT DEFAULT 0,
    max_retries     INT DEFAULT 3,
    next_retry_at   TIMESTAMPTZ,
    locked_by       VARCHAR(128),
    locked_until    TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    error_message   TEXT,
    calculation_result JSONB,
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now(),
    completed_at    TIMESTAMPTZ
);

CREATE UNIQUE INDEX idx_calc_jobs_active_dedup
    ON calculation_jobs (ocid, preset_no)
    WHERE status IN ('REQUESTED', 'API_REQUESTED', 'SNAPSHOT_READY', 'CALCULATING', 'RETRYING');

CREATE INDEX idx_calc_jobs_status ON calculation_jobs (status)
    WHERE status NOT IN ('COMPLETED', 'FAILED');

CREATE INDEX idx_calc_jobs_stale ON calculation_jobs (updated_at)
    WHERE status IN ('API_REQUESTED', 'RETRYING')
      AND locked_until IS NULL;

CREATE INDEX idx_calc_jobs_ocid ON calculation_jobs (ocid, preset_no);

CREATE TABLE calculation_snapshots (
    snapshot_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id          UUID NOT NULL REFERENCES calculation_jobs(job_id),
    object_key      VARCHAR(512) NOT NULL,
    storage_type    VARCHAR(16) NOT NULL DEFAULT 'LOCAL',
    character_id    VARCHAR(64),
    preset_no       INT DEFAULT 1,
    compressed_size BIGINT,
    original_size   BIGINT,
    hash            VARCHAR(128),
    expires_at      TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_snapshots_job_id ON calculation_snapshots (job_id);
CREATE INDEX idx_snapshots_expires ON calculation_snapshots (expires_at)
    WHERE expires_at IS NOT NULL;

SELECT pgmq.create('nexon_api_request_queue');
SELECT pgmq.create('nexon_api_response_queue');

CREATE INDEX IF NOT EXISTS idx_pgmq_api_req_job_id
    ON pgmq.q_nexon_api_request_queue ((message ->> 'jobId'));
CREATE INDEX IF NOT EXISTS idx_pgmq_api_res_job_id
    ON pgmq.q_nexon_api_response_queue ((message ->> 'jobId'));
