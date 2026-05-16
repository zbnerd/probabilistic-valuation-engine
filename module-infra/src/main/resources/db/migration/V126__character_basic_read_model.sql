-- CHARACTER_BASIC read model for V6 read path
CREATE TABLE IF NOT EXISTS character_basic_read_model (
    user_ign        TEXT PRIMARY KEY,
    ocid            TEXT NOT NULL,
    world_name      TEXT,
    character_class  TEXT,
    character_level  INT,
    guild_name      TEXT,
    basic_data      BYTEA NOT NULL,
    document_hash   TEXT,
    source_run_id   TEXT NOT NULL,
    source_chunk_id TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_basic_read_model_ocid UNIQUE (ocid)
);

CREATE INDEX idx_basic_read_model_ocid ON character_basic_read_model (ocid);
