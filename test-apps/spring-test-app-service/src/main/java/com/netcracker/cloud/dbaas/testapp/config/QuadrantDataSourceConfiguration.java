package com.netcracker.cloud.dbaas.testapp.config;

import com.netcracker.cloud.context.propagation.core.ContextManager;
import com.netcracker.cloud.dbaas.client.management.DatabaseConfig;
import com.netcracker.cloud.dbaas.client.management.DatabasePool;
import com.netcracker.cloud.dbaas.client.management.DbaasPostgresProxyDataSource;
import com.netcracker.cloud.dbaas.client.management.classifier.DbaasClassifierFactory;
import com.netcracker.cloud.framework.contexts.tenant.DefaultTenantProvider;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.List;

/**
 * Produces the two role-scoped datasources that the dbaas-client's default beans do not cover.
 *
 * <p>The starter only auto-creates {@code servicePostgresDatasource} (service, no role — Q1); unlike the
 * Quarkus extension it does NOT create a tenant datasource by default. We build the other three here,
 * mirroring exactly how the starter builds its default
 * ({@code new DbaasPostgresProxyDataSource(pool, classifierBuilder, config)}) so they inherit the same
 * global Flyway post-connect migration, differing only in scope/userRole. The four datasources together
 * cover every (scope × role) quadrant against its own mounted secret:
 * <pre>
 *   servicePostgresDatasource (Q1, from starter)   serviceAdminPostgresDatasource  service, admin (Q2)
 *   quadrantTenantPostgresDatasource (Q3)          tenantAdminPostgresDatasource   tenant,  admin (Q4)
 * </pre>
 */
@Configuration
public class QuadrantDataSourceConfiguration {

    public static final String SERVICE_ADMIN_POSTGRES_DATASOURCE = "serviceAdminPostgresDatasource";
    // Tenant + no role (Q3). The starter has no default tenant datasource, so we build our own.
    public static final String TENANT_POSTGRES_DATASOURCE = "quadrantTenantPostgresDatasource";
    public static final String TENANT_ADMIN_POSTGRES_DATASOURCE = "tenantAdminPostgresDatasource";

    // Role for the role-scoped quadrants (Q2/Q4); MUST match the role on the matching mounted secret.
    @Value("${sample.dbaas.user-role:admin}")
    private String userRole;

    // The tenant datasources resolve tenantId from the platform TenantContext; register the default
    // provider so a fixed tenant set per request (see QuadrantPostgresController) is visible to the
    // classifier — both for tenantPostgresDatasource (Q3) and tenantAdminPostgresDatasource (Q4).
    @PostConstruct
    void registerTenantProvider() {
        ContextManager.register(List.of(new DefaultTenantProvider()));
    }

    @Bean(SERVICE_ADMIN_POSTGRES_DATASOURCE)
    public DataSource serviceAdminPostgresDatasource(DatabasePool databasePool,
                                                     DbaasClassifierFactory classifierFactory) {
        return new DbaasPostgresProxyDataSource(databasePool,
                classifierFactory.newServiceClassifierBuilder(),
                DatabaseConfig.builder().userRole(userRole).build());
    }

    @Bean(TENANT_POSTGRES_DATASOURCE)
    public DataSource quadrantTenantPostgresDatasource(DatabasePool databasePool,
                                                       DbaasClassifierFactory classifierFactory) {
        return new DbaasPostgresProxyDataSource(databasePool,
                classifierFactory.newTenantClassifierBuilder(),
                DatabaseConfig.builder().build());
    }

    @Bean(TENANT_ADMIN_POSTGRES_DATASOURCE)
    public DataSource tenantAdminPostgresDatasource(DatabasePool databasePool,
                                                    DbaasClassifierFactory classifierFactory) {
        return new DbaasPostgresProxyDataSource(databasePool,
                classifierFactory.newTenantClassifierBuilder(),
                DatabaseConfig.builder().userRole(userRole).build());
    }
}
