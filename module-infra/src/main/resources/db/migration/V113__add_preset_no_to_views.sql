-- V113__add_preset_no_to_views.sql
-- Add preset_no column to character_valuation_views table for single-preset calculation support

-- Add preset_no column with default value 1 for backward compatibility
ALTER TABLE character_valuation_views
ADD COLUMN IF NOT EXISTS preset_no INT NOT NULL DEFAULT 1;

-- Add comment
COMMENT ON COLUMN character_valuation_views.preset_no
    IS 'Preset number for this view record (1-3). Default 1 for backward compatibility.';
