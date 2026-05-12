UPDATE backup SET status = 'FAILED' WHERE status IS NULL;
UPDATE backup_logical SET status = 'FAILED' WHERE status IS NULL;
UPDATE backup_database SET status = 'FAILED' WHERE status IS NULL;
UPDATE restore SET status = 'FAILED' WHERE status IS NULL;
UPDATE restore_logical SET status = 'FAILED' WHERE status IS NULL;
UPDATE restore_database SET status = 'FAILED' WHERE status IS NULL;

ALTER TABLE backup ALTER COLUMN status SET NOT NULL;
ALTER TABLE backup_logical ALTER COLUMN status SET NOT NULL;
ALTER TABLE backup_database ALTER COLUMN status SET NOT NULL;
ALTER TABLE restore ALTER COLUMN status SET NOT NULL;
ALTER TABLE restore_logical ALTER COLUMN status SET NOT NULL;
ALTER TABLE restore_database ALTER COLUMN status SET NOT NULL;
