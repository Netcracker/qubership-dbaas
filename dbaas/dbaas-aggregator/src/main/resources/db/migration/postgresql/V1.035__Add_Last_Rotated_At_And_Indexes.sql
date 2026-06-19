ALTER TABLE database
    ADD COLUMN IF NOT EXISTS last_rotated_at timestamptz;

CREATE INDEX IF NOT EXISTS idx_database_last_rotated_at
    ON database (last_rotated_at)
    WHERE last_rotated_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_classifier_database_id
    ON classifier (database_id);
