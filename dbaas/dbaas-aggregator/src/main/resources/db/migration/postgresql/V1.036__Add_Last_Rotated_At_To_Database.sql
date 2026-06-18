alter table database
    add column last_rotated_at timestamptz;

create index idx_database_last_rotated_at
    on database (last_rotated_at)
    where last_rotated_at is not null;
