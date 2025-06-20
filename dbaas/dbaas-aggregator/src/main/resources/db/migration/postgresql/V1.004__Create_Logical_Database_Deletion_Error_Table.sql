create table if not exists logical_db_operation_error
(
    id           uuid not null,
    database_id  uuid not null,
    time_db_operation  timestamp,
    error_message     text,
    http_code         int,
    operation         varchar(10),
    primary key (id),
    FOREIGN KEY (database_id) REFERENCES database (id) ON DELETE CASCADE
);
