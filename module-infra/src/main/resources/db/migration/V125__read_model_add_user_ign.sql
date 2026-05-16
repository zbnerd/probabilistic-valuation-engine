-- V125__read_model_add_user_ign.sql
-- NOTE: NOT NULL 제약은 V126에서 추가. Synchronizer 배포 전까지 nullable 허용.

-- Step 1: 컬럼 추가 (nullable — V126에서 NOT NULL 전환)
ALTER TABLE character_equipment_read_model
    ADD COLUMN user_ign TEXT;

-- Step 2: Backfill — game_character에서 ocid로 userIgn 역조회
UPDATE character_equipment_read_model r
SET user_ign = gc.user_ign
FROM game_character gc
WHERE r.ocid = gc.ocid;

-- Step 3: V6 batch 조회용 인덱스
CREATE INDEX idx_equipment_read_model_user_ign_preset
    ON character_equipment_read_model (user_ign, preset_no);
