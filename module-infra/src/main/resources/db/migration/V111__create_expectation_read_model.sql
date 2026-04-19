-- V111__create_expectation_read_model.sql
CREATE TABLE IF NOT EXISTS character_expectation_read_model (
    user_ign       VARCHAR(100) PRIMARY KEY,
    payload        BYTEA NOT NULL,
    calculated_at  TIMESTAMPTZ NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE character_expectation_read_model
    IS 'V5 Query Server read model. GZIP compressed full V5 response payload. LOGGED for replica replication.';

-- Index for cleanup queries
CREATE INDEX IF NOT EXISTS idx_expectation_read_model_calculated_at
  ON character_expectation_read_model(calculated_at);

-- [Consensus P0-2] Atomic UPSERT function (ON CONFLICT)
CREATE OR REPLACE FUNCTION upsert_expectation_read_model(
    p_user_ign      VARCHAR,
    p_payload       BYTEA,
    p_calculated_at TIMESTAMPTZ
) RETURNS void AS $$
BEGIN
    INSERT INTO character_expectation_read_model (user_ign, payload, calculated_at, updated_at)
    VALUES (p_user_ign, p_payload, p_calculated_at, NOW())
    ON CONFLICT (user_ign) DO UPDATE SET
        payload = EXCLUDED.payload,
        calculated_at = EXCLUDED.calculated_at,
        updated_at = NOW();
END;
$$ LANGUAGE plpgsql;
