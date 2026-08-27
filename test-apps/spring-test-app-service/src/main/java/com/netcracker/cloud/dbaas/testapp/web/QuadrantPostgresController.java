package com.netcracker.cloud.dbaas.testapp.web;

import com.netcracker.cloud.context.propagation.core.ContextManager;
import com.netcracker.cloud.dbaas.testapp.config.QuadrantDataSourceConfiguration;
import com.netcracker.cloud.dbaas.testapp.domain.Item;
import com.netcracker.cloud.framework.contexts.tenant.TenantContextObject;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static com.netcracker.cloud.framework.contexts.tenant.BaseTenantProvider.TENANT_CONTEXT_NAME;

/**
 * Endpoints for the remaining three (scope × role) quadrants, each backed by its own DBaaS-resolved
 * datasource matched from its own mounted secret (Q1 service+no-role lives in {@link PostgresController}):
 * <pre>
 *   /postgres-admin         service + role admin   (Q2)  -> serviceAdminPostgresDatasource
 *   /postgres-tenant        tenant  + no role      (Q3)  -> tenantPostgresDatasource
 *   /postgres-tenant-admin  tenant  + role admin   (Q4)  -> tenantAdminPostgresDatasource
 * </pre>
 *
 * <p>Tenant datasources read {@code tenantId} from the platform TenantContext, so the tenant endpoints
 * pin a fixed tenant ({@value #FIXED_TENANT}) before touching the datasource — making the classifier a
 * static {@code {scope=tenant, tenantId=acme}} that matches the mounted secret (dynamic per-request
 * tenants would miss and fall back to REST). The Flyway post-connect migration creates
 * {@code spring_test_app_items} in each resolved database on first connection.
 */
@RestController
public class QuadrantPostgresController {

    static final String FIXED_TENANT = "acme";

    private static final RowMapper<Item> ITEM_MAPPER = (rs, rowNum) -> new Item(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate serviceAdmin;
    private final JdbcTemplate tenant;
    private final JdbcTemplate tenantAdmin;

    public QuadrantPostgresController(
            @Qualifier(QuadrantDataSourceConfiguration.SERVICE_ADMIN_POSTGRES_DATASOURCE) DataSource serviceAdminDs,
            @Qualifier(QuadrantDataSourceConfiguration.TENANT_POSTGRES_DATASOURCE) DataSource tenantDs,
            @Qualifier(QuadrantDataSourceConfiguration.TENANT_ADMIN_POSTGRES_DATASOURCE) DataSource tenantAdminDs) {
        this.serviceAdmin = new JdbcTemplate(serviceAdminDs);
        this.tenant = new JdbcTemplate(tenantDs);
        this.tenantAdmin = new JdbcTemplate(tenantAdminDs);
    }

    // ── Q2: service + admin ──────────────────────────────────────────────────
    @PostMapping("/postgres-admin/items")
    public ResponseEntity<Map<String, Object>> createAdmin(@RequestBody(required = false) PostgresController.CreateItemRequest request) {
        return create(serviceAdmin, request);
    }

    @GetMapping("/postgres-admin/items")
    public Map<String, Object> listAdmin() {
        return list(serviceAdmin);
    }

    @DeleteMapping("/postgres-admin/items")
    public Map<String, Object> deleteAdmin() {
        return deleteAll(serviceAdmin);
    }

    // ── Q3: tenant + no role ─────────────────────────────────────────────────
    @PostMapping("/postgres-tenant/items")
    public ResponseEntity<Map<String, Object>> createTenant(@RequestBody(required = false) PostgresController.CreateItemRequest request) {
        pinTenant();
        return create(tenant, request);
    }

    @GetMapping("/postgres-tenant/items")
    public Map<String, Object> listTenant() {
        pinTenant();
        return list(tenant);
    }

    @DeleteMapping("/postgres-tenant/items")
    public Map<String, Object> deleteTenant() {
        pinTenant();
        return deleteAll(tenant);
    }

    // ── Q4: tenant + admin ───────────────────────────────────────────────────
    @PostMapping("/postgres-tenant-admin/items")
    public ResponseEntity<Map<String, Object>> createTenantAdmin(@RequestBody(required = false) PostgresController.CreateItemRequest request) {
        pinTenant();
        return create(tenantAdmin, request);
    }

    @GetMapping("/postgres-tenant-admin/items")
    public Map<String, Object> listTenantAdmin() {
        pinTenant();
        return list(tenantAdmin);
    }

    @DeleteMapping("/postgres-tenant-admin/items")
    public Map<String, Object> deleteTenantAdmin() {
        pinTenant();
        return deleteAll(tenantAdmin);
    }

    private void pinTenant() {
        ContextManager.set(TENANT_CONTEXT_NAME, new TenantContextObject(FIXED_TENANT));
    }

    private ResponseEntity<Map<String, Object>> create(JdbcTemplate jdbc, PostgresController.CreateItemRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
        }
        Item created = jdbc.queryForObject(
                "INSERT INTO spring_test_app_items(name) VALUES (?) RETURNING id, name, created_at",
                ITEM_MAPPER, request.name().trim());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("item", created));
    }

    private Map<String, Object> list(JdbcTemplate jdbc) {
        List<Item> items = jdbc.query("SELECT id, name, created_at FROM spring_test_app_items ORDER BY id", ITEM_MAPPER);
        return Map.of("items", items);
    }

    private Map<String, Object> deleteAll(JdbcTemplate jdbc) {
        return Map.of("deleted", jdbc.update("DELETE FROM spring_test_app_items"));
    }
}
