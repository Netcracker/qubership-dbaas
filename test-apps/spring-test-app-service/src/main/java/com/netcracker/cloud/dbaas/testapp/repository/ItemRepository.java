package com.netcracker.cloud.dbaas.testapp.repository;

import com.netcracker.cloud.dbaas.testapp.domain.Item;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DML over the DBaaS-resolved PostgreSQL datasource. The injected
 * {@code servicePostgresDatasource} is a lazy proxy: the first query triggers database
 * resolution (mounted secret first, REST fallback) and the Flyway post-connect migration,
 * so the {@code spring_test_app_items} table exists before any statement runs.
 */
@Repository
public class ItemRepository {

    private static final RowMapper<Item> ITEM_MAPPER = (rs, rowNum) -> new Item(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    // Bean name constant DbaasPostgresConfiguration.SERVICE_POSTGRES_DATASOURCE.
    public ItemRepository(@Qualifier("servicePostgresDatasource") DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public Item insert(String name) {
        return jdbc.queryForObject(
                "INSERT INTO spring_test_app_items(name) VALUES (?) RETURNING id, name, created_at",
                ITEM_MAPPER, name);
    }

    public List<Item> findAll() {
        return jdbc.query("SELECT id, name, created_at FROM spring_test_app_items ORDER BY id", ITEM_MAPPER);
    }

    public int deleteAll() {
        return jdbc.update("DELETE FROM spring_test_app_items");
    }

    public int ping() {
        Integer result = jdbc.queryForObject("SELECT 1", Integer.class);
        return result == null ? 0 : result;
    }

    public Map<String, Object> connectionInfo() {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("url", metaData.getURL());
            info.put("username", metaData.getUserName());
            return info;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read connection properties", e);
        }
    }
}
