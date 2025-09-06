create table v2_backup
(
    name varchar primary key,
    storage_name varchar not null,
    blob_path varchar not null,
    external_database_strategy varchar not null,
    filters jsonb,
    status varchar,
    total int,
    completed int,
    size bigint,
    error_message varchar,
    attempt_count int default 0
);

create table v2_logical_backup
(
    id uuid primary key,
    logical_backup_name varchar,
    backup_name varchar references v2_backup(name),
    adapter_id varchar,
    type varchar not null,
    status varchar,
    error_message varchar,
    creation_time timestamp default now(),
    completion_time timestamp
);

create table v2_backup_database
(
    id uuid primary key,
    logical_backup_id uuid references v2_logical_backup(id),
    name varchar,
    classifiers jsonb not null,
    settings jsonb,
    users jsonb not null,
    resources jsonb,
    status varchar,
    size bigint,
    duration bigint,
    path varchar,
    error_message varchar,
    creation_time timestamp default now(),
    completion_time timestamp
);

create table v2_backup_external_database
(
    id uuid primary key,
    backup_name varchar references v2_backup(name),
    name varchar not null,
    type varchar not null,
    classifiers jsonb not null
);

create table v2_restore
(
    name varchar primary key,
    backup_name varchar references v2_backup(name),
    storage_name varchar not null,
    blob_path varchar not null,
    filters jsonb,
    mapping jsonb,
    status jsonb,
    attempt_count int default 0
);

create table v2_logical_restore
(
    id uuid primary key,
    logical_restore_name varchar,
    restore_name varchar references v2_restore(name),
    adapter_id varchar not null,
    type varchar not null,
    status jsonb not null
);

create table v2_restore_database
(
    id uuid primary key,
    logical_restore_id uuid references v2_logical_restore(id),
    backup_db_id uuid references v2_backup_database(id),
    name varchar,
    classifiers jsonb not null,
    users jsonb not null,
    resources jsonb
);
