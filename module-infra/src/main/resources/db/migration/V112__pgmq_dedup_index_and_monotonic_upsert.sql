-- P1: Expression index for PGMQ dedup query optimization
-- Hot path: ExpectationCalculationQueue.enqueue() -> findActiveMessageIdByUserIgn
CREATE INDEX IF NOT EXISTS idx_pgmq_high_user_ign ON pgmq.q_expectation_calc_high ((message ->> 'userIgn'));
CREATE INDEX IF NOT EXISTS idx_pgmq_low_user_ign ON pgmq.q_expectation_calc_low ((message ->> 'userIgn'));

-- P2: Atomic send-if-absent function to eliminate TOCTOU race in queue dedup
-- Replaces: findActiveMessageIdByUserIgn() + send() with single atomic call
CREATE OR REPLACE FUNCTION pgmq_send_if_absent(
    p_queue_name VARCHAR,
    p_user_ign VARCHAR,
    p_payload JSONB
) RETURNS BIGINT AS $$
DECLARE
    v_existing_msg_id BIGINT;
    v_new_msg_id BIGINT;
    v_queue_table TEXT;
BEGIN
    v_queue_table := 'q_' || p_queue_name;

    EXECUTE format('SELECT msg_id FROM pgmq.%I WHERE message ->> ''userIgn'' = $1 ORDER BY msg_id DESC LIMIT 1', v_queue_table)
        INTO v_existing_msg_id
        USING p_user_ign;

    IF v_existing_msg_id IS NOT NULL THEN
        RETURN -v_existing_msg_id;  -- Negative = reused existing message
    END IF;

    EXECUTE format('SELECT pgmq.send($1, $2)') INTO v_new_msg_id USING p_queue_name, p_payload;
    RETURN v_new_msg_id;  -- Positive = new message sent
END;
$$ LANGUAGE plpgsql;

-- P3: Monotonic read-model upsert - prevent stale data overwrite
CREATE OR REPLACE FUNCTION upsert_expectation_read_model(
    p_user_ign VARCHAR,
    p_payload BYTEA,
    p_calculated_at TIMESTAMPTZ
) RETURNS void AS $$
BEGIN
    INSERT INTO character_expectation_read_model (user_ign, payload, calculated_at, updated_at)
    VALUES (p_user_ign, p_payload, p_calculated_at, NOW())
    ON CONFLICT (user_ign) DO UPDATE SET
        payload = EXCLUDED.payload,
        calculated_at = EXCLUDED.calculated_at,
        updated_at = NOW()
    WHERE EXCLUDED.calculated_at >= character_expectation_read_model.calculated_at;
END;
$$ LANGUAGE plpgsql;
