create or replace function public.notify_database_changes() returns trigger as
$BODY$
begin
    if (TG_OP = 'DELETE') then
        perform pg_notify('database_table_changes_event', old.id::text);
        return old;
    end if;
    perform pg_notify('database_table_changes_event', new.id::text);
    return new;
end;
$BODY$
    language plpgsql;

create or replace function public.notify_physical_database_changes() returns trigger as
$BODY$
begin
    if (TG_OP = 'DELETE') then
        perform pg_notify('physical_database_table_changes_event', old.id::text);
        return old;
    end if;
    perform pg_notify('physical_database_table_changes_event', new.id::text);
    return new;
end;
$BODY$
    language plpgsql;

drop trigger if exists database_changes_trigger on public.database;

create trigger database_changes_trigger
    after update or insert or delete on public.database
    for each row
    execute procedure public.notify_database_changes();

drop trigger if exists physical_database_changes_trigger on public.physical_database;

create trigger physical_database_changes_trigger
    after update or insert or delete on public.physical_database
    for each row
    execute procedure public.notify_physical_database_changes();