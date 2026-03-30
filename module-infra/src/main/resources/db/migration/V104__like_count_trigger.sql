-- V104__like_count_trigger.sql
-- #664: like_count 원자성 보장 — DB Trigger 도입
-- character_like INSERT/DELETE 시 game_character.like_count 자동 증감

-- ============================================================
-- 1. Trigger Function
-- ============================================================
CREATE OR REPLACE FUNCTION fn_like_count_trigger()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        UPDATE game_character
        SET like_count = GREATEST(COALESCE(like_count, 0) + 1, 0),
            updated_at = NOW()
        WHERE ocid = NEW.target_ocid;
        IF NOT FOUND THEN
            RAISE WARNING 'Like count trigger: no game_character found for ocid=%', NEW.target_ocid;
        END IF;
        RETURN NEW;
    ELSIF TG_OP = 'DELETE' THEN
        UPDATE game_character
        SET like_count = GREATEST(COALESCE(like_count, 0) - 1, 0),
            updated_at = NOW()
        WHERE ocid = OLD.target_ocid;
        IF NOT FOUND THEN
            RAISE WARNING 'Like count trigger: no game_character found for ocid=%', OLD.target_ocid;
        END IF;
        RETURN OLD;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- ============================================================
-- 2. Trigger: AFTER INSERT OR DELETE on character_like
-- ============================================================
DROP TRIGGER IF EXISTS trg_like_count ON character_like;
CREATE TRIGGER trg_like_count
    AFTER INSERT OR DELETE ON character_like
    FOR EACH ROW EXECUTE FUNCTION fn_like_count_trigger();

-- ============================================================
-- 3. Reconciliation: 기존 drift 수정
--    pg_try_advisory_xact_lock으로 rolling update 중인
--    incrementLikeCount와의 deadlock 방지
--    NOTE: lock 획득 실패 시 해당 row는 skip됨 (다음 마이그레이션 재실행 시 재시도)
-- ============================================================
WITH correct AS (
    SELECT target_ocid, COUNT(*) AS cnt
    FROM character_like
    GROUP BY target_ocid
)
UPDATE game_character gc
SET like_count = COALESCE(c.cnt, 0), updated_at = NOW()
FROM correct c
WHERE gc.ocid = c.target_ocid
  AND gc.like_count != c.cnt
  AND pg_try_advisory_xact_lock(hashtext(gc.ocid));

-- zero-like characters with stale count
UPDATE game_character
SET like_count = 0, updated_at = NOW()
WHERE like_count != 0
  AND NOT EXISTS (
      SELECT 1 FROM character_like WHERE target_ocid = game_character.ocid
  );

-- Comments
COMMENT ON FUNCTION fn_like_count_trigger() IS 'Auto-increment like_count on character_like INSERT/DELETE (#664)';
COMMENT ON TRIGGER trg_like_count ON character_like IS 'Syncs like_count with character_like row count (#664)';
