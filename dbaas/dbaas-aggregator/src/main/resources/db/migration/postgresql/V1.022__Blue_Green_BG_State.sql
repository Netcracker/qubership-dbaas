create table if not exists bg_domain
(
    id            uuid not null,
    primary key (id)
);

create table if not exists bg_namespace
(
    namespace text      not null,
    bg_domain_id uuid   not null,
    state text          not null,
    version text,
    update_time timestamp,
    FOREIGN KEY (bg_domain_id) REFERENCES bg_domain (id) ON DELETE CASCADE,
    primary key (namespace)
)
