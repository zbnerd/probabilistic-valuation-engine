-- calculation_snapshot_inputs: External API Path가 저장하는 CalculationInput
CREATE TABLE calculation_snapshot_inputs (
    input_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id          UUID NOT NULL UNIQUE REFERENCES calculation_jobs(job_id),
    schema_version  INT DEFAULT 1,
    payload         JSONB NOT NULL,
    created_at      TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_snapshot_inputs_job ON calculation_snapshot_inputs (job_id);

-- calculation_results: Write Path가 저장하는 gzip 압축 계산 결과
CREATE TABLE calculation_results (
    result_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id           UUID NOT NULL UNIQUE REFERENCES calculation_jobs(job_id),
    character_class  VARCHAR(64),
    preset_no        INT DEFAULT 1,
    schema_version   INT DEFAULT 1,
    content_type     VARCHAR(64) DEFAULT 'application/json',
    content_encoding VARCHAR(16) DEFAULT 'gzip',
    response_body    BYTEA,
    original_size    INT,
    compressed_size  INT,
    hash             VARCHAR(128),
    status           VARCHAR(16) DEFAULT 'SUCCESS',
    created_at       TIMESTAMPTZ DEFAULT now(),
    expires_at       TIMESTAMPTZ
);

CREATE INDEX idx_calc_results_job ON calculation_results (job_id);
CREATE INDEX idx_calc_results_char ON calculation_results (character_class, preset_no);
CREATE INDEX idx_calc_results_expires ON calculation_results (expires_at) WHERE expires_at IS NOT NULL;

-- outbox_events: 이벤트 발행 보장
CREATE TABLE outbox_events (
    event_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type       VARCHAR(64) NOT NULL,
    job_id           UUID NOT NULL,
    payload          JSONB,
    published        BOOLEAN DEFAULT false,
    publish_attempts INT DEFAULT 0,
    created_at       TIMESTAMPTZ DEFAULT now(),
    published_at     TIMESTAMPTZ,
    UNIQUE (job_id, event_type)
);

CREATE INDEX idx_outbox_unpublished ON outbox_events (published, created_at) WHERE published = false;
