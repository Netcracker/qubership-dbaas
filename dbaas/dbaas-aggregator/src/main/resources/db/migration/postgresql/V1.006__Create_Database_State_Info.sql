create table if not exists database_state_info
(
    id                 uuid not null,
    state              varchar(15),
    description        text,
    primary key (id)
);

ALTER TABLE database ADD COLUMN IF NOT EXISTS dbstate_id uuid;
ALTER TABLE database DROP CONSTRAINT IF EXISTS fk_db_state;
ALTER TABLE database ADD CONSTRAINT fk_db_state FOREIGN KEY (dbstate_id) REFERENCES database_state_info (id) ON DELETE CASCADE;
