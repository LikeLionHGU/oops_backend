-- Manual MySQL migration; NOT executed automatically.
-- Back up and inspect SHOW COLUMNS FROM analysis_coverage first.
-- Apply only if context_limited is absent. local/dev ddl-auto=update adds it automatically.
ALTER TABLE analysis_coverage ADD COLUMN context_limited BOOLEAN NULL;
-- NULL on historical rows means unrecorded, not unlimited context.
-- Do not backfill historical PARTIAL results or rewrite their reports.
