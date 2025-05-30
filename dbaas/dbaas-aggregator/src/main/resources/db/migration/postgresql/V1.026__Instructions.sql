create table if not exists physical_database_instruction
(
    id                    uuid not null,
    physical_database_id  text not null,
    context               text not null,
    instruction_type      text not null,
    time_creation         timestamp not null,
    physical_db_reg_request text not null,
    primary key (id)
);
