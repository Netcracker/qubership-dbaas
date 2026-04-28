create table if not exists composite_properties
(
   composite_namespace_id uuid primary key
       references composite_namespace(id)
           on delete cascade,
   modify_index numeric(20) not null check (modify_index >= 0)
);
