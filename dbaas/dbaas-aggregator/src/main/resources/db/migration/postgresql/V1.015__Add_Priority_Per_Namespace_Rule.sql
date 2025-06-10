ALTER TABLE per_namespace_rule
    ADD COLUMN IF NOT EXISTS rule_type text DEFAULT 'NAMESPACE';

