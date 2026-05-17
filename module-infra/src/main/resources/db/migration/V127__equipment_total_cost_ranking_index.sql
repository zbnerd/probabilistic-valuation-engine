CREATE INDEX IF NOT EXISTS idx_equipment_read_model_total_cost_rank
    ON character_equipment_read_model (preset_no, total_cost DESC)
    INCLUDE (user_ign)
    WHERE user_ign IS NOT NULL;
