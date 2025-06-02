ALTER TABLE database_role
    ADD COLUMN IF NOT EXISTS disable_global_permissions boolean DEFAULT false;