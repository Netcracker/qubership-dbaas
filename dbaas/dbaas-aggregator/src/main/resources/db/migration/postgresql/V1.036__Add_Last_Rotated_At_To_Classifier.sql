-- Marker stamped on a registry row whenever its credentials change (password rotation or restore).
-- Consumed by the dbaas-operator rotation poller via the changed-databases query.
-- Left NULL for existing rows: they are already synced by the operator's startup reconcile,
-- and a NULL marker keeps them out of the change feed until their next rotation.
alter table classifier
    add column last_rotated_at timestamptz;

-- Composite (last_rotated_at, id) so the operator's keyset query
-- "(last_rotated_at, id) > (since_ts, since_id) order by last_rotated_at, id"
-- and the high-water-mark lookup (order by last_rotated_at desc, id desc) are index-served.
create index idx_classifier_last_rotated_at
    on classifier (last_rotated_at, id)
    where last_rotated_at is not null;
