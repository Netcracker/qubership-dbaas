package com.netcracker.cloud.dbaas.testapp.quarkus;

import com.netcracker.cloud.context.propagation.core.ContextManager;
import com.netcracker.cloud.core.quarkus.dbaas.datasource.DbaasQuarkusPostgresqlDatasourceBuilder;
import com.netcracker.cloud.core.quarkus.dbaas.datasource.classifier.MicroserviceClassifierBuilder;
import com.netcracker.cloud.core.quarkus.dbaas.datasource.classifier.TenantClassifierBuilder;
import com.netcracker.cloud.dbaas.client.management.DatabaseConfig;
import com.netcracker.cloud.framework.contexts.tenant.DefaultTenantProvider;
import io.agroal.api.AgroalDataSource;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Produces the two role-scoped datasources that the extension's default beans do not cover.
 *
 * <p>{@code serviceDataSource}/{@code tenantDataSource} are built by the extension with no
 * {@code userRole}, so they only exercise the no-role quadrants. To exercise the role dimension we
 * build datasources with an explicit {@code userRole} through the extension's public builder. The
 * four datasources together cover every (scope × role) quadrant against its own mounted secret:
 * <pre>
 *   serviceDataSource       service, no role   (Q1)   serviceAdminDataSource  service, admin (Q2)
 *   tenantDataSource        tenant,  no role   (Q3)   tenantAdminDataSource   tenant,  admin (Q4)
 * </pre>
 */
@Singleton
public class QuadrantDataSourceProducer {

    public static final String SERVICE_ADMIN_DATASOURCE = "serviceAdminDataSource";
    public static final String TENANT_ADMIN_DATASOURCE = "tenantAdminDataSource";

    @ConfigProperty(name = "cloud.microservice.name")
    String microserviceName;

    @ConfigProperty(name = "cloud.microservice.namespace")
    String namespace;

    @ConfigProperty(name = "sample.dbaas.user-role", defaultValue = "admin")
    String userRole;

    @Inject
    DbaasQuarkusPostgresqlDatasourceBuilder datasourceBuilder;

    // The tenant datasources resolve tenantId from the platform TenantContext; register the default
    // provider so a fixed tenant set per request (see TenantPostgresResource) is visible to the classifier.
    void onStart(@Observes StartupEvent ev) {
        ContextManager.register(List.of(new DefaultTenantProvider()));
    }

    @Produces
    @Named(SERVICE_ADMIN_DATASOURCE)
    @ApplicationScoped
    public AgroalDataSource serviceAdminDataSource() {
        return (AgroalDataSource) datasourceBuilder
                .newBuilder(new MicroserviceClassifierBuilder(classifierParams()))
                .withDatabaseConfig(DatabaseConfig.builder().userRole(userRole).build())
                .build();
    }

    @Produces
    @Named(TENANT_ADMIN_DATASOURCE)
    @ApplicationScoped
    public AgroalDataSource tenantAdminDataSource() {
        return (AgroalDataSource) datasourceBuilder
                .newBuilder(new TenantClassifierBuilder(classifierParams()))
                .withDatabaseConfig(DatabaseConfig.builder().userRole(userRole).build())
                .build();
    }

    private Map<String, Object> classifierParams() {
        Map<String, Object> params = new HashMap<>();
        params.put("microserviceName", microserviceName);
        params.put("namespace", namespace);
        return params;
    }
}
