ALTER TABLE physical_database_instruction
    ADD COLUMN IF NOT EXISTS failures jsonb DEFAULT '[]'::jsonb;
