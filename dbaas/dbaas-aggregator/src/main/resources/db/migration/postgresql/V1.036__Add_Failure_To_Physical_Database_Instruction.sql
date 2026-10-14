ALTER TABLE physical_database_instruction
    ADD COLUMN IF NOT EXISTS failure text;
