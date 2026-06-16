-- Marker stamped on a database row whenever its credentials change (password rotation or restore).
-- It lives on `database` (where connection_properties live) rather than per-classifier, since the
-- credentials are shared across all of a database's registries. Consumed by the dbaas-operator rotation
-- poller via the changed-databases query, which returns the affected registries (classifiers).
-- Left NULL for existing rows: they are already synced by the operator's startup reconcile, and a NULL
-- marker keeps them out of the change feed until their next rotation.
alter table database
    add column last_rotated_at timestamptz;

-- Narrows the changed set; the keyset tie-break is the registry (classifier) id, served by its own PK.
create index idx_database_last_rotated_at
    on database (last_rotated_at)
    where last_rotated_at is not null;
