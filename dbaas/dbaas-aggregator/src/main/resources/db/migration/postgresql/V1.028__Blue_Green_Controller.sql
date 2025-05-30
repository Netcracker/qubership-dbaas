ALTER TABLE bg_domain
    ADD COLUMN IF NOT EXISTS controller_namespace text;
