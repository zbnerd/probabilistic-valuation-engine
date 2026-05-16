# CHARACTER_BASIC Synchronizer Pipeline Design

## Goal
CHARACTER_BASIC snapshot chunks를 Kafka → Synchronizer → DB 파이프라인으로 저장.

## Architecture
```
External API (CHARACTER_BASIC chunk ready)
  → Kafka "external-api.snapshot.chunk-ready" (endpoint=character-basic)
  → Synchronizer BasicSnapshotChunkConsumer
  → Read gzip JSONL chunk file
  → Parse: ocid (key), character_name/level/class/world (body)
  → gzip compress body
  → Bulk upsert to character_basic_read_model
```

## Table: character_basic_read_model
```sql
CREATE TABLE character_basic_read_model (
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
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    updated_at      TIMESTAMPTZ DEFAULT NOW(),
    CONSTRAINT uq_basic_ocid UNIQUE (ocid)
);
```

## Changes
- DB: V126 migration
- External API: characterBasicSnapshotPublisher Kafka 활성화
- Synchronizer: BasicSnapshotChunkConsumer + BasicChunkFileReader + CharacterBasicRepository
- Config: application.yml 양쪽 모두
