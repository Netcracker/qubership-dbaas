create table backup
(
    name                            varchar primary key,
    storage_name                    varchar not null,
    blob_path                       varchar not null,
    external_database_strategy      varchar not null,
    filter_criteria                 jsonb,
    status                          varchar,
    total                           int,
    completed                       int,
    size                            bigint,
    error_message                   varchar,
    attempt_count                   int default 0,
    imported                        boolean,
    digest                          varchar,
    ignore_not_backupable_databases boolean
);

create table backup_logical
(
    id                  uuid primary key,
    logical_backup_name varchar,
    backup_name         varchar references backup(name),
    adapter_id          varchar,
    type                varchar not null,
    status              varchar,
    error_message       varchar,
    creation_time       timestamp with time zone,
    completion_time     timestamp with time zone
);

create table backup_database
(
    id                uuid primary key,
    logical_backup_id uuid references backup_logical(id),
    name              varchar,
    classifiers       jsonb not null,
    settings          jsonb,
    users             jsonb not null,
    configurational   boolean,
    status            varchar,
    size              bigint,
    duration          bigint,
    path              varchar,
    error_message     varchar,
    creation_time     timestamp with time zone
);

create table backup_external_database
(
    id          uuid primary key,
    backup_name varchar references backup(name),
    name        varchar not null,
    type        varchar not null,
    classifiers jsonb not null
);

create table restore
(
    name                       varchar primary key,
    backup_name                varchar references backup(name),
    storage_name               varchar not null,
    blob_path                  varchar not null,
    external_database_strategy varchar not null,
    filter_criteria            jsonb,
    mapping                    jsonb,
    status                     varchar,
    total                      int,
    completed                  int,
    duration                   bigint,
    error_message              varchar,
    attempt_count              int default 0
);

create table restore_logical
(
    id                   uuid primary key,
    logical_restore_name  varchar,
    restore_name         varchar references restore(name),
    adapter_id           varchar not null,
    type                 varchar not null,
    status               varchar,
    error_message        varchar,
    creation_time        timestamp with time zone,
    completion_time      timestamp with time zone
);

create table restore_database
(
    id                 uuid primary key,
    logical_restore_id uuid references restore_logical(id),
    backup_db_id       uuid references backup_database(id),
    name               varchar,
    classifiers        jsonb not null,
    users              jsonb not null,
    settings           jsonb,
    bgversion          varchar,
    status             varchar,
    duration           bigint,
    path               varchar,
    error_message      varchar,
    creation_time      timestamp with time zone
);

create table restore_external_database
(
    id           uuid primary key,
    restore_name varchar references restore(name),
    name         varchar not null,
    type         varchar not null,
    classifiers  jsonb not null
);

create table shedlock
(
    name       varchar(64) not null primary key,
    lock_until timestamp not null,
    locked_at  timestamp not null,
    locked_by  varchar(255) not null
);
