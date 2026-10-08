-- Manual MySQL migration. Not executed by this change.
-- Back up the target DB, inspect SHOW COLUMNS FROM screen_text, and add only missing columns.
-- Do not execute this unchanged if Hibernate ddl-auto=update already created these columns.
-- Existing rows remain NULL/UNCERTAIN. Never backfill them as EDITORIAL without evidence.
ALTER TABLE screen_text
    ADD COLUMN box_x DOUBLE NULL,
    ADD COLUMN box_y DOUBLE NULL,
    ADD COLUMN box_width DOUBLE NULL,
    ADD COLUMN box_height DOUBLE NULL,
    ADD COLUMN track_id VARCHAR(255) NULL,
    ADD COLUMN observations INT NULL,
    ADD COLUMN slot_text_changes INT NULL,
    ADD COLUMN text_role VARCHAR(24) NULL,
    ADD COLUMN role_reason VARCHAR(200) NULL;

-- Rollback: restore the paired Spring/Python code versions; retain these nullable columns and data.
-- Do not drop region metadata to roll back application code.
