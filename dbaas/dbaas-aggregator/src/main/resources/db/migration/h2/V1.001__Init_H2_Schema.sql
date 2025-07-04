CREATE TYPE "JSONB" AS json;

create table if not exists database_state_info
(
    id             uuid not null,
    state          varchar(15),
    database_state varchar(15),
    description    text,
    pod_name       text,
    primary key (id)
);

create table database
(
    id                     uuid    not null,
    adapter_id             text,
    backup_disabled        boolean,
    old_classifier         JSONB,
    classifier             JSONB,
    connection_description text,
    connection_properties  text,
    db_owner_roles         text,
    externally_manageable  boolean not null,
    marked_for_drop        boolean not null,
    name                   text,
    namespace              text,
    settings               text,
    time_db_creation       timestamp,
    type                   varchar,
    warnings               text,
    dbstate_id             uuid,
    physical_database_id   text,
    bgVersion              text,
    FOREIGN KEY (dbstate_id) REFERENCES database_state_info (id) ON DELETE CASCADE,
    primary key (id)
);

create table classifier
(
    id               uuid not null,
    classifier       jsonb,
    namespace        text,
    type             text,
    time_db_creation timestamp,
    database_id      uuid,
    FOREIGN KEY (database_id) REFERENCES database (id) ON DELETE CASCADE,
    primary key (id)
);

create table external_adapter_registration
(
    adapter_id             varchar(255) not null unique,
    address                text,
    http_basic_credentials text,
    supported_version      text,
    api_versions           text,
    primary key (adapter_id)
);


create table db_resources
(
    id   uuid not null,
    kind varchar(64),
    name text,
    primary key (id)
);

create table database_db_resources
(
    database_id  uuid not null,
    resources_id uuid not null unique,
    FOREIGN KEY (database_id) REFERENCES database (id),
    FOREIGN KEY (resources_id) REFERENCES db_resources (id)
);

create table physical_database
(
    id                           varchar(255) not null,
    global                       boolean      not null,
    unidentified                 boolean,
    labels                       text,
    physical_database_identifier text not null unique,
    registration_date            timestamp,
    type                         text,
    roles                        text default '["admin"]',
    features                     jsonb,
    adapter_external_adapter_id  varchar(255),
    ro_host                      text,
    primary key (id),
    FOREIGN KEY (adapter_external_adapter_id) REFERENCES external_adapter_registration (adapter_id) ON DELETE CASCADE
);

create table if not exists logical_db_operation_error
(
    id                uuid not null,
    database_id       uuid not null,
    time_db_operation timestamp,
    error_message     text,
    http_code         int,
    operation         varchar(10),
    primary key (id),
    FOREIGN KEY (database_id) REFERENCES database (id) ON DELETE CASCADE
);

create table if not exists composite_namespace
(
    id                uuid primary key,
    baseline          varchar(63) not null,
    namespace         varchar(63) not null
);

ALTER TABLE database
    ADD COLUMN IF NOT EXISTS dbstate_id uuid;
ALTER TABLE database
    DROP CONSTRAINT IF EXISTS fk_db_state;
ALTER TABLE database
    ADD CONSTRAINT fk_db_state FOREIGN KEY (dbstate_id) REFERENCES database_state_info (id);

create table users (
    id          uuid not null,
    username    text,
    password    text,
    primary key (id)
);

create table roles (
    user_id     uuid not null,
    role        text not null,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);