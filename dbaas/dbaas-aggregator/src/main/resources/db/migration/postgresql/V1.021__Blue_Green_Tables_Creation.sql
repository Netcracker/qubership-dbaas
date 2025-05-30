ALTER TABLE public.database
    ADD COLUMN IF NOT EXISTS bgVersion text;

ALTER TABLE public.namespace_backup
    ADD COLUMN IF NOT EXISTS database_registries jsonb;

ALTER TABLE public.classifier
    ADD CONSTRAINT fk_database FOREIGN KEY (database_id) REFERENCES database (id);

create or replace function public.notify_classifier_changes() returns trigger as
$BODY$
begin
    if (TG_OP = 'DELETE') then
        perform pg_notify('classifier_table_changes_event', old.id::text);
        return old;
    end if;
    perform pg_notify('classifier_table_changes_event', new.id::text);
    return new;
end;
$BODY$
    language plpgsql;

drop trigger if exists classifier_changes_trigger on public.classifier;

create trigger classifier_changes_trigger
    after update or insert or delete on public.classifier
    for each row
    execute procedure public.notify_classifier_changes();


create table if not exists database_declarative_config
(
    id                     uuid    not null,
    settings               text,
    lazy                   boolean not null,
    instantiation_approach text,
    versioning_approach    text,
    versioning_type        text,
    classifier             jsonb   not null,
    type                   text    not null,
    physical_database_id   text,
    name_prefix            text,
    namespace              text    not null,
    primary key (id)
);