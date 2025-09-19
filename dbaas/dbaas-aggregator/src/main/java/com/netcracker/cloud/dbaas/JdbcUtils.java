package com.netcracker.cloud.dbaas;

import com.netcracker.cloud.security.core.utils.tls.TlsUtils;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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

    private static final String SSL_URL_PARAMS = "?ssl=true&sslfactory=org.postgresql.ssl.SingleCertValidatingFactory&sslfactoryarg=";

    public static String buildConnectionURL(String pgHost, String pgPort, String pgDatabase) {
        String url = String.format("jdbc:postgresql://%s:%s/%s", pgHost, pgPort, pgDatabase);
        if (isInternalTlsEnabled()) {
            log.debug("Going to use secured connection to postgres");
            String rootCertificatePath = "file://" + TlsUtils.getCaCertificatePath();
            url += SSL_URL_PARAMS + rootCertificatePath;
        }
        log.debug("Using not secured connection to postgres");
        return url;
    }

    private static boolean isInternalTlsEnabled() {
        String internalTlsEnabled = System.getenv("INTERNAL_TLS_ENABLED");
        return Boolean.parseBoolean(internalTlsEnabled);
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
