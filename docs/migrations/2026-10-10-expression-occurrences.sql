-- Manual MySQL migration; NOT executed by this change.
-- Back up the target DB and inspect SHOW CREATE TABLE video / expression_occurrence first.
-- H2 local and dev ddl-auto=update create this table automatically; do not blindly run both paths.
-- video_id must match the deployed video.id type. This draft assumes signed BIGINT.
CREATE TABLE IF NOT EXISTS expression_occurrence (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    video_id BIGINT NOT NULL,
    expression_id VARCHAR(64) NOT NULL,
    category VARCHAR(40) NOT NULL,
    matched_text VARCHAR(80) NOT NULL,
    segment_id VARCHAR(80) NOT NULL,
    start_ms BIGINT NOT NULL,
    end_ms BIGINT NOT NULL,
    start_offset INT NOT NULL,
    end_offset INT NOT NULL,
    dictionary_version VARCHAR(80) NOT NULL,
    common_usage_note VARCHAR(300) NULL,
    INDEX idx_expression_video (video_id),
    CONSTRAINT fk_expression_video FOREIGN KEY (video_id) REFERENCES video(id)
);
-- Rollback application code without dropping the table or captured mentions.
-- Old reports have no EXPRESSION_SCAN coverage and must remain NOT_ANALYZED, not silently SUCCESS.
