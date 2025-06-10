create table if not exists database_role
(
    id                 uuid not null,
    microservice_name  text,
    namespace          text,
    services           text,
    policies           text,
    time_role_creation timestamp,
    primary key (id)
);

UPDATE public.database set connection_properties = '[{"role": "admin", '::text || substr(connection_properties, 2, length(connection_properties) - 1) || ']'::text;

ALTER TABLE physical_database ADD COLUMN IF NOT EXISTS roles text DEFAULT '["admin"]';

ALTER TABLE external_adapter_registration ADD COLUMN IF NOT EXISTS supported_version text DEFAULT 'v1';

ALTER TABLE public.database ADD COLUMN IF NOT EXISTS physical_database_id text;

UPDATE database
SET physical_database_id = physical_database_table.physical_database_identifier
FROM (
    SELECT adapter_external_adapter_id, physical_database_identifier
    FROM physical_database) AS physical_database_table
WHERE
    physical_database_table.adapter_external_adapter_id = adapter_id
