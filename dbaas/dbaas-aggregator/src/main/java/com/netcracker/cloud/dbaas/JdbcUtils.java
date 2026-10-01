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
import java.util.function.Function;

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

    static final String POSTGRES_TLS_ENABLED = "POSTGRES_TLS_ENABLED";
    static final String POSTGRES_TLS_CA_CERT_PATH = "POSTGRES_TLS_CA_CERT_PATH";
    // Before POSTGRES_TLS_ENABLED existed, this flag enabled PostgreSQL TLS. It now controls only the inbound HTTPS
    // listener, so it is read here as a fallback for deployments that have not set POSTGRES_TLS_ENABLED yet.
    static final String LEGACY_INTERNAL_TLS_ENABLED = "INTERNAL_TLS_ENABLED";

    private static final String DEFAULT_CERTIFICATE_STORE_PATH = "/etc/tls";
    private static final String SSL_URL_PARAMS = "&ssl=true&sslfactory=org.postgresql.ssl.SingleCertValidatingFactory&sslfactoryarg=file://";
    private static final String POD_SECRETS_PATH = "/etc/secrets/pod-secrets";
    private static final String ACTIVE_DR_MODE = "active";

    public static String resolveConnectionURL() {
        return resolveConnectionURL(JdbcUtils::getParameterValue);
    }

    /**
     * Builds the PostgreSQL JDBC URL from the given parameter lookup, which returns {@code null} for an unset parameter.
     */
    static String resolveConnectionURL(Function<String, String> parameters) {
        String host = valueOrDefault(parameters, "POSTGRES_HOST", DEFAULT_HOST);
        String port = valueOrDefault(parameters, "POSTGRES_PORT", DEFAULT_PORT);
        String database = valueOrDefault(parameters, "POSTGRES_DATABASE", DEFAULT_DATABASE_NAME);
        boolean ssl = resolvePostgresTlsEnabled(parameters);
        String caCertificatePath = valueOrDefault(parameters, POSTGRES_TLS_CA_CERT_PATH,
                valueOrDefault(parameters, "CERTIFICATE_FILE_PATH", DEFAULT_CERTIFICATE_STORE_PATH) + "/ca.crt");
        return buildConnectionURL(host, port, database, ssl, caCertificatePath);
    }

    private static boolean resolvePostgresTlsEnabled(Function<String, String> parameters) {
        String postgresTls = parameters.apply(POSTGRES_TLS_ENABLED);
        if (postgresTls != null) {
            return Boolean.parseBoolean(postgresTls.strip());
        }
        String legacyTls = parameters.apply(LEGACY_INTERNAL_TLS_ENABLED);
        if (legacyTls != null) {
            log.warn("{} is not set; using {}={} for the PostgreSQL connection. {} now controls only the inbound HTTPS "
                            + "listener. Set {} explicitly.",
                    POSTGRES_TLS_ENABLED, LEGACY_INTERNAL_TLS_ENABLED, legacyTls.strip(), LEGACY_INTERNAL_TLS_ENABLED,
                    POSTGRES_TLS_ENABLED);
            return Boolean.parseBoolean(legacyTls.strip());
        }
        return DEFAULT_SSL_ENABLED;
    }

    private static String valueOrDefault(Function<String, String> parameters, String name, String defaultValue) {
        String value = parameters.apply(name);
        return value != null ? value : defaultValue;
    }

    public static String resolveUsername() {
        return getParameterValue("POSTGRES_USER", DEFAULT_USERNAME);
    }

    public static String resolvePassword() {
        return getParameterValue("POSTGRES_PASSWORD", DEFAULT_PASSWORD);
    }

    public static String buildConnectionURL(String host, String port, String database, boolean ssl) {
        String caCertificatePath = getParameterValue("CERTIFICATE_FILE_PATH", DEFAULT_CERTIFICATE_STORE_PATH) + "/ca.crt";
        return buildConnectionURL(host, port, database, ssl, caCertificatePath);
    }

    static String buildConnectionURL(String host, String port, String database, boolean ssl, String caCertificatePath) {
        String url = String.format("jdbc:postgresql://%s:%s/%s", host, port, database);
        url += "?connectTimeout=10&socketTimeout=30";

        if (ACTIVE_DR_MODE.equalsIgnoreCase(getParameterValue("EXECUTION_MODE", ""))) {
            url += "&targetServerType=primary";
        }

        if (ssl) {
            log.info("Using secured connection to postgres with CA certificate {}", caCertificatePath);
            url += SSL_URL_PARAMS + caCertificatePath;
        } else {
            log.info("Using not secured connection to postgres");
        }

        return url;
    }

    private static String getParameterValue(String name, String defaultValue) {
        String value = getParameterValue(name);
        return value != null ? value : defaultValue;
    }

    private static String getParameterValue(String name) {
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
        return null;
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
