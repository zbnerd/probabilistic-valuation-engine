-- V124__read_model_document_hash.sql
-- Add document_hash for skip-unchanged optimization via ON CONFLICT WHERE IS DISTINCT FROM

ALTER TABLE character_equipment_read_model
    ADD COLUMN IF NOT EXISTS document_hash text;
