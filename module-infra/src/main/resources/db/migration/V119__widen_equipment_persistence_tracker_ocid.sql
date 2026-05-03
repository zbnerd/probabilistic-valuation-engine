-- Widen equipment_persistence_tracker.ocid from VARCHAR(20) to VARCHAR(64)
-- Real Nexon OCID values exceed 20 characters, causing DataIntegrityViolationException
-- Aligns with calculation_jobs.ocid (VARCHAR(64)) and other tables
ALTER TABLE equipment_persistence_tracker ALTER COLUMN ocid TYPE VARCHAR(64);
