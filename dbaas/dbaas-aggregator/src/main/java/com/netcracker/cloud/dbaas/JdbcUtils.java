package com.netcracker.cloud.dbaas;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
@Slf4j
public class JdbcUtils {
    public static final String DEFAULT_HOST = "localhost";
    public static final String DEFAULT_PORT = "5432";
    public static final String DEFAULT_DATABASE_NAME = "dbaas";
    public static final String DEFAULT_USERNAME = "dbaas";
    public static final String DEFAULT_PASSWORD = "dbaas";
    public static final boolean DEFAULT_SSL_ENABLED = false;
    public static final String PROCESS_ORCHESTRATOR_DATASOURCE = "process-orchestrator";

    private static final String CERTIFICATE_STORE_PATH = getParameterValue("CERTIFICATE_FILE_PATH", "/etc/tls");
    private static final String CA_CERTIFICATE_URL = "file://" + CERTIFICATE_STORE_PATH + "/ca.crt";
    private static final String SSL_URL_PARAMS = "?ssl=true&sslfactory=org.postgresql.ssl.SingleCertValidatingFactory&sslfactoryarg=" + CA_CERTIFICATE_URL;
    private static final String POD_SECRETS_PATH = "/etc/secrets/pod-secrets";

    public static String resolveConnectionURL() {
        String host = getParameterValue("POSTGRES_HOST", DEFAULT_HOST);
        String port = getParameterValue("POSTGRES_PORT", DEFAULT_PORT);
        String database = getParameterValue("POSTGRES_DATABASE", DEFAULT_DATABASE_NAME);
        boolean ssl = Boolean.parseBoolean(getParameterValue("INTERNAL_TLS_ENABLED", Boolean.toString(DEFAULT_SSL_ENABLED)));
        return buildConnectionURL(host, port, database, ssl);
    }

    public static String resolveUsername() {
        return getParameterValue("POSTGRES_USER", DEFAULT_USERNAME);
    }

    public static String resolvePassword() {
        return getParameterValue("POSTGRES_PASSWORD", DEFAULT_PASSWORD);
    }

    public static String buildConnectionURL(String host, String port, String database, boolean ssl) {
        String url = String.format("jdbc:postgresql://%s:%s/%s", host, port, database);
        if (ssl) {
            log.info("Using secured connection to postgres");
            url += SSL_URL_PARAMS;
        } else {
            log.info("Using not secured connection to postgres");
        }
        return url;
    }

    private static String getParameterValue(String name, String defaultValue) {
        String value = System.getProperty(name, System.getenv().get(name));
        if (value != null) {
            return value;
        }
        Path secretFile = Paths.get(POD_SECRETS_PATH, name);
        if (Files.exists(secretFile)) {
            try {
                return Files.readString(secretFile).strip();
            } catch (IOException e) {
                log.warn("Failed to read secret file {}", secretFile, e);
            }
        }
        return defaultValue;
    }

    public static List<Map<String, Object>> queryForList(Connection connection, String query) throws SQLException {
        ResultSet resultSet = connection.createStatement().executeQuery(query);
        List<Map<String, Object>> rowData = new ArrayList<>();
        ResultSetMetaData resultSetMetaData = resultSet.getMetaData();
        List<String> columnNames = new ArrayList<>();
        for (int i = 1; i <= resultSetMetaData.getColumnCount(); i++) {
            columnNames.add(resultSetMetaData.getColumnName(i));
        }
        while (resultSet.next()) {
            Map<String, Object> row = new HashMap<>();
            for (String columnName : columnNames) {
                row.put(columnName, resultSet.getObject(columnName));
            }
            rowData.add(row);
        }
        return rowData;
    }
}
