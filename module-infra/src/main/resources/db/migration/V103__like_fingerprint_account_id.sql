-- V103__like_fingerprint_account_id.sql
-- #662: fingerprint column for self-like prevention (multi-character OCID resolution)
-- #663: account_id column for upsert idempotency

-- Add fingerprint column (nullable for lazy backfill via JWT filter)
ALTER TABLE game_character ADD COLUMN IF NOT EXISTS fingerprint VARCHAR(64);

-- Add account_id column (= fingerprint, for semantic clarity and future migration)
ALTER TABLE game_character ADD COLUMN IF NOT EXISTS account_id VARCHAR(64);

-- Covering index for fingerprint→ocid resolution (hot path: every auth request)
CREATE INDEX IF NOT EXISTS idx_game_character_fingerprint
    ON game_character (fingerprint, ocid) WHERE fingerprint IS NOT NULL;

-- Index for account_id lookups
CREATE INDEX IF NOT EXISTS idx_game_character_account_id
    ON game_character (account_id) WHERE account_id IS NOT NULL;

-- Partial unique: one account cannot register the same IGN twice
CREATE UNIQUE INDEX IF NOT EXISTS uk_account_user_ign
    ON game_character (account_id, user_ign) WHERE account_id IS NOT NULL;

-- Comments
COMMENT ON COLUMN game_character.fingerprint IS 'API Key fingerprint (HMAC-SHA256 hash) for self-like prevention';
COMMENT ON COLUMN game_character.account_id IS 'Account identity (= fingerprint) for upsert idempotency';
