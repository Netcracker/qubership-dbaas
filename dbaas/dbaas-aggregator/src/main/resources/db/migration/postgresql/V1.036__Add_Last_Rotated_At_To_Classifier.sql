-- Marker stamped on a registry row whenever its credentials change (password rotation or restore).
-- Consumed by the dbaas-operator rotation poller via the changed-databases query.
-- Left NULL for existing rows: they are already synced by the operator's startup reconcile,
-- and a NULL marker keeps them out of the change feed until their next rotation.
alter table classifier
    add column last_rotated_at timestamptz;

create index idx_classifier_last_rotated_at
    on classifier (last_rotated_at)
    where last_rotated_at is not null;
