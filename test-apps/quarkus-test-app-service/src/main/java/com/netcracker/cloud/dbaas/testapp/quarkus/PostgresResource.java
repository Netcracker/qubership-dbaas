package com.netcracker.cloud.dbaas.testapp.quarkus;

import com.netcracker.cloud.core.quarkus.dbaas.datasource.service.MigrationService;
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
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PostgreSQL endpoints exercised by the integration test. The injected {@code serviceDataSource} is
 * produced by the dbaas-datasource extension and resolves from the mounted secret before any REST
 * call to dbaas-aggregator; so when the secret is mounted the service keeps working even if the
 * aggregator is unreachable.
 *
 * <p>The {@code quarkus_test_app_items} table is created by the dbaas Flyway integration
 * ({@link MigrationService#migrate}), run lazily on the first request so the pod can boot before the
 * database is reachable. Migrations live in {@code classpath:db/migration}; {@code baseline-version=0}
 * lets {@code V1} apply on top of the (non-empty) dbaas-provisioned schema.
 */
@Path("/postgres")
@Produces(MediaType.APPLICATION_JSON)
public class PostgresResource {

    @Inject
    @Named("serviceDataSource")
    AgroalDataSource dataSource;

    @Inject
    MigrationService migrationService;

    private final AtomicBoolean migrated = new AtomicBoolean(false);

    private void ensureMigrated() {
        if (migrated.get()) {
            return;
        }
        synchronized (this) {
            if (!migrated.get()) {
                migrationService.migrate(dataSource);
                migrated.set(true);
            }
        }
    }

    @POST
    @Path("/items")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response create(CreateItemRequest request) throws SQLException {
        if (request == null || request.name == null || request.name.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "name is required")).build();
        }
        ensureMigrated();
        try (Connection c = dataSource.getConnection();
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

    @GET
    @Path("/items")
    public Map<String, Object> list() throws SQLException {
        ensureMigrated();
        List<Item> items = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT id, name, created_at FROM quarkus_test_app_items ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                items.add(new Item(rs.getLong("id"), rs.getString("name"), rs.getTimestamp("created_at").toInstant()));
            }
        }
        return Map.of("items", items);
    }

    @DELETE
    @Path("/items")
    public Map<String, Object> deleteAll() throws SQLException {
        ensureMigrated();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM quarkus_test_app_items")) {
            return Map.of("deleted", ps.executeUpdate());
        }
    }

    @GET
    @Path("/ping")
    public Map<String, Object> ping() throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT 1");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return Map.of("status", "ok", "result", rs.getInt(1));
        }
    }

    @GET
    @Path("/connection-properties")
    public Map<String, Object> connectionProperties() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            DatabaseMetaData metaData = c.getMetaData();
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("url", metaData.getURL());
            info.put("username", metaData.getUserName());
            return info;
        }
    }

    public static class CreateItemRequest {
        public String name;
    }
}
