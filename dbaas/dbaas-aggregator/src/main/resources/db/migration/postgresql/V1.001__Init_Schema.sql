CREATE TABLE _mongo_migrated (
   flag bool
);

INSERT INTO _mongo_migrated (flag) VALUES (false);

create table database
(
    id                     uuid    not null,
    adapter_id             text,
    backup_disabled        boolean,
    classifier             jsonb,
    connection_description text,
    connection_properties  text,
    db_owner_roles         text,
    externally_manageable  boolean not null,
    marked_for_drop        boolean not null,
    name                   text,
    namespace              text,
    settings               text,
    time_db_creation       timestamp,
    type                   text,
    warnings               text,
    primary key (id)
);

create UNIQUE INDEX classifier_and_type_index ON public.database USING btree (classifier, type) WHERE ((classifier ->> 'MARKED_FOR_DROP'::text) IS NULL);

create table databases_backup
(
    id         uuid         not null,
    adapter_id varchar(255) not null,
    databases  text,
    local_id   text,
    status     varchar(255),
    track_id   text,
    track_path text,
    primary key (id)
);

create table external_adapter_registration
(
    adapter_id             varchar(255) not null unique,
    address                text,
    http_basic_credentials text,
    primary key (adapter_id)
);

create table namespace_backup
(
    id           uuid not null,
    created      timestamp,
    fail_reasons text,
    namespace    text,
    status       varchar(255),
    databases    jsonb,
    primary key (id)
);

create table namespace_backup_database
(
    namespacebackup_id uuid not null,
    databases_id        uuid not null,
    FOREIGN KEY (namespacebackup_id) REFERENCES namespace_backup (id)
);

create table namespace_backup_databases_backup
(
    namespacebackup_id uuid not null,
    backups_id          uuid not null unique,
    FOREIGN KEY (backups_id) REFERENCES databases_backup (id),
    FOREIGN KEY (namespacebackup_id) REFERENCES namespace_backup (id)
);

create table namespace_restoration
(
    id           uuid not null,
    fail_reasons text,
    status       varchar(64),
    primary key (id)
);

create table namespace_backup_namespace_restoration
(
    namespacebackup_id uuid not null,
    restorations_id     uuid not null,
    FOREIGN KEY (restorations_id) REFERENCES namespace_restoration (id),
    FOREIGN KEY (namespacebackup_id) REFERENCES namespace_backup (id)
);

create table restore_result
(
    id                  uuid not null,
    adapter_id          text,
    changed_name_db     text,
    status              varchar(255),
    databases_backup_id uuid,
    primary key (id),
    FOREIGN KEY (databases_backup_id) REFERENCES databases_backup (id)
);

create table namespace_restoration_restore_result
(
    namespacerestoration_id uuid not null,
    restoreresults_id       uuid not null unique,
    FOREIGN KEY (restoreresults_id) REFERENCES restore_result (id),
    FOREIGN KEY (namespacerestoration_id) REFERENCES namespace_restoration (id)
);

create table db_resources
(
    id            uuid not null,
    kind          varchar(64),
    name          text,
    primary key (id)
);

create table database_db_resources
(
    database_id        uuid not null,
    resources_id       uuid not null unique,
    FOREIGN KEY (database_id) REFERENCES database (id),
    FOREIGN KEY (resources_id) REFERENCES db_resources (id)
);

create table per_namespace_rule
(
    name                         varchar(255) not null,
    database_type                text,
    namespace                    text,
    ordering                     bigint,
    physical_database_identifier text not null unique,
    primary key (name)
);

create table physical_database
(
    id                           varchar(255) not null,
    global                       boolean      not null,
    labels                       text,
    physical_database_identifier text,
    registration_date            timestamp,
    type                         text,
    adapter_external_adapter_id  varchar(255),
    primary key (id),
    FOREIGN KEY (adapter_external_adapter_id) REFERENCES external_adapter_registration (adapter_id)
);

create table tracked_action
(
    track_id        varchar(255) not null,
    action          varchar(255),
    adapter_id      text,
    changed_name_db text,
    created_time_ms bigint,
    details         text,
    finished        boolean,
    status          varchar(255),
    track_path      text,
    when_checked    timestamp,
    when_finished   timestamp,
    when_started    timestamp,
    primary key (track_id)
);

create table database_history
(
    id            uuid not null,
    change_action varchar(255),
    classifier    text,
    date          timestamp,
    name          text,
    type          text,
    version       integer,
    database      jsonb,
    primary key (id)
);