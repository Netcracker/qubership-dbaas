ALTER TABLE bg_domain
    ADD COLUMN IF NOT EXISTS origin_namespace text;
ALTER TABLE bg_domain
    ADD COLUMN IF NOT EXISTS peer_namespace text;