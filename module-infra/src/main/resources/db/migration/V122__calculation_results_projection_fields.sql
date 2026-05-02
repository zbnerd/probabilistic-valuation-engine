-- V122__calculation_results_projection_fields.sql
-- Add projection fields to avoid BYTEA transfer + GZIP decompression + JSON parsing in ResultReadyProjectionWorker

ALTER TABLE calculation_results
    ADD COLUMN IF NOT EXISTS total_expected_cost BIGINT,
    ADD COLUMN IF NOT EXISTS max_preset_no INT,
    ADD COLUMN IF NOT EXISTS presets JSONB;

COMMENT ON COLUMN calculation_results.total_expected_cost IS 'Pre-extracted total expected cost for projection (avoids BYTEA decompress)';
COMMENT ON COLUMN calculation_results.max_preset_no IS 'Pre-extracted max preset number for projection (avoids BYTEA decompress)';
COMMENT ON COLUMN calculation_results.presets IS 'Pre-extracted presets JSON for projection (avoids BYTEA decompress)';
