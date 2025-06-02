package org.qubership.cloud.dbaas.repositories.queries;

public final class DebugLogicalDatabaseQueries {

    public static final String FIND_DEBUG_LOGICAL_DATABASES = """
        SELECT
            logical_database.classifier AS logical_database_classifier,
            logical_database.bgVersion AS logical_database_bgVersion,
            logical_database.type AS logical_database_type,
            logical_database.connection_properties AS logical_database_connection_properties,
            logical_database.name AS logical_database_name,
            logical_database.physical_database_id AS logical_database_physical_database_id,
            external_adapter_registration.address AS external_adapter_registration_address,
            database_declarative_config.id AS database_declarative_config_id,
            database_declarative_config.settings AS database_declarative_config_settings,
            database_declarative_config.lazy AS database_declarative_config_lazy,
            database_declarative_config.instantiation_approach AS database_declarative_config_instantiation_approach,
            database_declarative_config.versioning_approach AS database_declarative_config_versioning_approach,
            database_declarative_config.versioning_type AS database_declarative_config_versioning_type,
            database_declarative_config.classifier AS database_declarative_config_classifier,
            database_declarative_config.type AS database_declarative_config_type,
            database_declarative_config.name_prefix AS database_declarative_config_name_prefix,
            database_declarative_config.namespace AS database_declarative_config_namespace
        
        FROM database as logical_database
        
        LEFT JOIN external_adapter_registration
            ON external_adapter_registration.adapter_id = logical_database.adapter_id
        
        LEFT JOIN database_declarative_config
            ON logical_database.classifier @> database_declarative_config.classifier
        """;

    private DebugLogicalDatabaseQueries() {}
}
