create table if not exists bg_track
(
    id             text not null,
    namespace      text,
    operation      text,
    primary key (id)
);


create table if not exists scheduled_tasks
(
    task_name            varchar(100),
    task_instance        varchar(100),
    task_data            bytea,
    execution_time       TIMESTAMP WITH TIME ZONE,
    picked               BOOLEAN,
    picked_by            varchar(50),
    last_success         TIMESTAMP WITH TIME ZONE,
    last_failure         TIMESTAMP WITH TIME ZONE,
    consecutive_failures INT,
    last_heartbeat       TIMESTAMP WITH TIME ZONE,
    version              BIGINT,
    PRIMARY KEY (task_name, task_instance)
);

create table if not exists po_context
(
    id           varchar(100),
    context_data bytea,
    version      BIGINT,
    PRIMARY KEY (id)
);

