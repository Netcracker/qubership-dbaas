package com.netcracker.cloud.dbaas.testapp.quarkus;

import com.netcracker.cloud.context.propagation.core.ContextManager;
import com.netcracker.cloud.core.quarkus.dbaas.datasource.service.MigrationService;
import com.netcracker.cloud.framework.contexts.tenant.TenantContextObject;
import io.agroal.api.AgroalDataSource;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.netcracker.cloud.framework.contexts.tenant.BaseTenantProvider.TENANT_CONTEXT_NAME;

/**
 * Endpoints for the remaining three (scope × role) quadrants, each backed by its own dbaas datasource
 * resolved from its own mounted secret (Q1 service+no-role lives in {@link PostgresResource}):
 * <pre>
 *   /postgres-admin         service + role admin   (Q2)  -> serviceAdminDataSource
 *   /postgres-tenant        tenant  + no role      (Q3)  -> tenantDataSource
 *   /postgres-tenant-admin  tenant  + role admin   (Q4)  -> tenantAdminDataSource
 * </pre>
 *
 * <p>Tenant datasources read {@code tenantId} from the platform TenantContext, so the tenant endpoints
 * pin a fixed tenant ({@value #FIXED_TENANT}) before touching the datasource — making the classifier a
 * static {@code {scope=tenant, tenantId=acme}} that matches the mounted secret (dynamic per-request
 * tenants would miss and fall back to REST).
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class QuadrantPostgresResource {

    static final String FIXED_TENANT = "acme";

    @Inject
    @Named(QuadrantDataSourceProducer.SERVICE_ADMIN_DATASOURCE)
    AgroalDataSource serviceAdminDs;

    @Inject
    @Named("tenantDataSource")
    AgroalDataSource tenantDs;

    @Inject
    @Named(QuadrantDataSourceProducer.TENANT_ADMIN_DATASOURCE)
    AgroalDataSource tenantAdminDs;

    @Inject
    MigrationService migrationService;

    private final Set<AgroalDataSource> migrated = ConcurrentHashMap.newKeySet();

    // ── Q2: service + admin ──────────────────────────────────────────────────
    @POST
    @Path("postgres-admin/items")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createAdmin(PostgresResource.CreateItemRequest request) throws SQLException {
        return create(serviceAdminDs, request);
    }

    @GET
    @Path("postgres-admin/items")
    public Map<String, Object> listAdmin() throws SQLException {
        return list(serviceAdminDs);
    }

    @DELETE
    @Path("postgres-admin/items")
    public Map<String, Object> deleteAdmin() throws SQLException {
        return deleteAll(serviceAdminDs);
    }

    // ── Q3: tenant + no role ─────────────────────────────────────────────────
    @POST
    @Path("postgres-tenant/items")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createTenant(PostgresResource.CreateItemRequest request) throws SQLException {
        pinTenant();
        return create(tenantDs, request);
    }

    @GET
    @Path("postgres-tenant/items")
    public Map<String, Object> listTenant() throws SQLException {
        pinTenant();
        return list(tenantDs);
    }

    @DELETE
    @Path("postgres-tenant/items")
    public Map<String, Object> deleteTenant() throws SQLException {
        pinTenant();
        return deleteAll(tenantDs);
    }

    // ── Q4: tenant + admin ───────────────────────────────────────────────────
    @POST
    @Path("postgres-tenant-admin/items")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createTenantAdmin(PostgresResource.CreateItemRequest request) throws SQLException {
        pinTenant();
        return create(tenantAdminDs, request);
    }

    @GET
    @Path("postgres-tenant-admin/items")
    public Map<String, Object> listTenantAdmin() throws SQLException {
        pinTenant();
        return list(tenantAdminDs);
    }

    @DELETE
    @Path("postgres-tenant-admin/items")
    public Map<String, Object> deleteTenantAdmin() throws SQLException {
        pinTenant();
        return deleteAll(tenantAdminDs);
    }

    private void pinTenant() {
        ContextManager.set(TENANT_CONTEXT_NAME, new TenantContextObject(FIXED_TENANT));
    }

    private void ensureMigrated(AgroalDataSource ds) {
        if (migrated.contains(ds)) {
            return;
        }
        synchronized (this) {
            if (!migrated.contains(ds)) {
                migrationService.migrate(ds);
                migrated.add(ds);
            }
        }
    }

    private Response create(AgroalDataSource ds, PostgresResource.CreateItemRequest request) throws SQLException {
        if (request == null || request.name == null || request.name.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "name is required")).build();
        }
        ensureMigrated(ds);
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO quarkus_test_app_items(name) VALUES (?) RETURNING id, name, created_at")) {
            ps.setString(1, request.name.trim());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Item item = new Item(rs.getLong("id"), rs.getString("name"), rs.getTimestamp("created_at").toInstant());
                return Response.status(Response.Status.CREATED).entity(Map.of("item", item)).build();
            }
        }
    }

    private Map<String, Object> list(AgroalDataSource ds) throws SQLException {
        ensureMigrated(ds);
        List<Item> items = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT id, name, created_at FROM quarkus_test_app_items ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                items.add(new Item(rs.getLong("id"), rs.getString("name"), rs.getTimestamp("created_at").toInstant()));
            }
        }
        return Map.of("items", items);
    }

    private Map<String, Object> deleteAll(AgroalDataSource ds) throws SQLException {
        ensureMigrated(ds);
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM quarkus_test_app_items")) {
            return Map.of("deleted", ps.executeUpdate());
        }
    }
}
