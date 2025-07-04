ALTER TABLE database_state_info
    ADD COLUMN IF NOT EXISTS database_state varchar(15);

UPDATE database_state_info
SET database_state =
        CASE
            WHEN state = '0' THEN 'PROCESSING'
            WHEN state = '1' THEN 'CREATED'
            WHEN state = '2' THEN 'DELETING'
            WHEN state = '3' THEN 'DELETING_FAILED'
            WHEN state = '4' THEN 'ARCHIVED'
            END
WHERE state IS NOT NULL;

