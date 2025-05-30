-- We must rollback migration changes from 1.018 to avoid inconsistency: https://tms.qubership.org/browse/PSUPCLFRM-7245
UPDATE database_state_info SET state =
CASE
   WHEN state = 'PROCESSING' THEN '0'
   WHEN state = 'CREATED' THEN '1'
   WHEN state = 'DELETING' THEN '2'
   WHEN state = 'DELETING_FAILED' THEN '3'
   WHEN state = 'ARCHIVED' THEN '4'
END
WHERE state IS NOT NULL;