create table if not exists per_microservice_rule
(
    id                           uuid not null,
    database_type                text not null,
    namespace                    text not null,
    microservice                 text not null,
    update_date                  timestamp not null,
    create_date                  timestamp not null,
    rules                        text not null,
    generation                   int not null,
    primary key (id)
);