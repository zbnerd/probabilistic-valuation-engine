-- V123__synchronizer_read_model_tables.sql
-- Synchronizer read model: ocid:presetNo 단위 gzip 압축 document 저장

-- Main read model (Web API 조회용)
create table if not exists character_equipment_read_model (
    read_key text primary key,
    ocid text not null,
    preset_no smallint not null,
    document bytea not null,
    total_cost numeric(20, 0) not null,
    equipment_count int not null,
    calculated_at timestamptz not null,
    source_run_id text not null,
    source_chunk_id text not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_character_equipment_ocid_preset
        unique (ocid, preset_no)
);

-- Chunk 처리 상태 추적
create table if not exists synchronizer_chunk_status (
    run_id text not null,
    chunk_id text not null,
    result_object_key text not null,
    status text not null,
    documents_count int not null default 0,
    items_count bigint not null default 0,
    error_message text,
    received_at timestamptz,
    started_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (run_id, chunk_id),
    constraint chk_synchronizer_chunk_status
        check (status in (
            'RECEIVED',
            'PROCESSING',
            'SUCCESS',
            'FAILED'
        ))
);
