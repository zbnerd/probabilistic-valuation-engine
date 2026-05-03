-- Create result_ready_queue for outbox relay (PR #771 Codex review)
SELECT pgmq.create('result_ready_queue');

-- Migrate legacy status values to current enum (PR #769 Codex review)
-- OCID_RESOLVED, OCID_RETRY_WAIT, API_RETRY_WAIT were removed from CalculationJobStatus
UPDATE calculation_jobs
SET status = 'RETRYING'
WHERE status IN ('OCID_RESOLVED', 'OCID_RETRY_WAIT', 'API_RETRY_WAIT');
