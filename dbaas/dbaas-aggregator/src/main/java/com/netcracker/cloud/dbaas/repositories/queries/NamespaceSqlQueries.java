package org.qubership.cloud.dbaas.repositories.queries;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class NamespaceSqlQueries {

    public static final String FIND_ALL_REGISTERED_NAMESPACES = """
        SELECT namespace FROM database
        UNION
        SELECT namespace FROM namespace_backup
        UNION
        SELECT namespace FROM per_namespace_rule
        UNION
        SELECT namespace FROM per_microservice_rule
        UNION
        SELECT namespace FROM database_declarative_config
        UNION
        SELECT namespace FROM bg_namespace
        UNION
        SELECT baseline FROM composite_namespace
        UNION
        SELECT namespace FROM composite_namespace
        """;
}
