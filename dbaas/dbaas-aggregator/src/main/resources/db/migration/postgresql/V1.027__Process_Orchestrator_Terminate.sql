create table if not exists pe_process_instance
(
    pi_id      varchar(100),
    name varchar(255),
    def_id     varchar(100),
    state varchar(255),
    version    BIGINT,
    start_time bigint,
    end_time   bigint,
    PRIMARY KEY (pi_id)
);
create table if not exists pe_task_instance
(
    task_id    varchar(100),
    def_id     varchar(100),
    pi_id      varchar(100),
    name varchar(255),
    state varchar(255),
    type varchar,
    depends_on bytea,
    version    BIGINT,
    PRIMARY KEY (task_id)
);

create table if not exists pe_process_definition
(
    pd_id   varchar(100),
    version BIGINT,
    PRIMARY KEY (pd_id)
);

create table if not exists pe_task_definition
(
    task_name  varchar(255),
    depends_on varchar,
    task_id    varchar(100),
    pd_id      varchar(100),
    version    BIGINT,
    PRIMARY KEY (task_id)
);