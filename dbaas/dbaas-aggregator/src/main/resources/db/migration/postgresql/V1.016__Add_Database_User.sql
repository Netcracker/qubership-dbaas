create table if not exists database_user
(
    id                     uuid         not null,
    database_id            uuid         not null,
    connection_properties  JSONB        not null,
    user_role              varchar(255) not null,
    created_date           timestamp    not null,
    logical_user_id        varchar(255),
    creation_method        varchar(255) not null,
    status                 varchar(255) not null,
    connection_description text,

    primary key (id),
    FOREIGN KEY (database_id) REFERENCES database (id)
);