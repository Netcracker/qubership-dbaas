package org.qubership.cloud.dbaas;

import org.qubership.cloud.dbaas.monitoring.AgroalDataSourceMetricsBinder;
import org.qubership.cloud.security.core.utils.tls.TlsUtils;
import io.agroal.api.AgroalDataSource;
import io.agroal.api.configuration.AgroalConnectionPoolConfiguration;
import io.agroal.api.configuration.supplier.AgroalConnectionFactoryConfigurationSupplier;
import io.agroal.api.configuration.supplier.AgroalConnectionPoolConfigurationSupplier;
import io.agroal.api.configuration.supplier.AgroalDataSourceConfigurationSupplier;
import io.agroal.api.security.AgroalDefaultSecurityProvider;
import io.agroal.api.security.AgroalSecurityProvider;
import io.agroal.api.security.NamePrincipal;
import io.agroal.api.security.SimplePassword;
import io.agroal.api.transaction.TransactionIntegration;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tag;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
@Slf4j
public class JdbcUtils {

    private static final String SSL_URL_PARAMS = "?ssl=true&sslfactory=org.postgresql.ssl.SingleCertValidatingFactory&sslfactoryarg=";

    public static AgroalDataSource buildDataSource(String pgHost, int pgPort, String pgDatabase,
                                                   String pgUser, String pgPassword, int maxPoolSize,
                                                   TransactionIntegration transactionIntegration) throws SQLException {
        String url = String.format("jdbc:postgresql://%s:%d/%s", pgHost, pgPort, pgDatabase);
        if (isInternalTlsEnabled()) {
            try {
                log.debug("Going to use secured connection to postgres");
                AgroalDataSource agroalDataSource = buildDataSource(true, url, pgUser, pgPassword, maxPoolSize, transactionIntegration);
                agroalDataSource.getConnection(); // check connection in tls mode
                return agroalDataSource;
            } catch (Exception e) {
                log.warn("TLS is enabled in dbaas-aggregator, but disabled in postgres");
            }
        }
        log.debug("Using not secured connection to postgres");
        return buildDataSource(false, url, pgUser, pgPassword, maxPoolSize, transactionIntegration);
    }

    private static boolean isInternalTlsEnabled() {
        String internalTlsEnabled = System.getenv("INTERNAL_TLS_ENABLED");
        return Boolean.parseBoolean(internalTlsEnabled);
    }

    private static AgroalDataSource buildDataSource(boolean withTls, String url, String pgUser,
                                                    String pgPassword, int maxPoolSize,
                                                    TransactionIntegration transactionIntegration) throws SQLException {
        if (withTls) {
            String rootCertificatePath = "file://" + TlsUtils.getCaCertificatePath();
            url += SSL_URL_PARAMS + rootCertificatePath;
        }
        log.info("Create data source with connection string: {}", url);
        return JdbcUtils.createDatasource(url, pgUser, pgPassword, maxPoolSize, transactionIntegration);
    }

    public static AgroalDataSource createDatasource(String url, String username, String password, int maxPoolSize, TransactionIntegration transactionIntegration) throws SQLException {
        AgroalDataSourceConfigurationSupplier dataSourceConfiguration = new AgroalDataSourceConfigurationSupplier();
        AgroalConnectionPoolConfigurationSupplier poolConfiguration = dataSourceConfiguration.connectionPoolConfiguration();
        AgroalConnectionFactoryConfigurationSupplier connectionFactoryConfiguration = poolConfiguration.connectionFactoryConfiguration();

        poolConfiguration.maxSize(maxPoolSize)
                .acquisitionTimeout(Duration.ofSeconds(10))
                .validationTimeout(Duration.ofSeconds(10))
                .connectionValidator(AgroalConnectionPoolConfiguration.ConnectionValidator.defaultValidator());
        if (transactionIntegration != null) {
            poolConfiguration.transactionIntegration(transactionIntegration);
        }

        SimplePassword simplePassword = new SimplePassword(password);
        connectionFactoryConfiguration.jdbcUrl(url)
                .principal(new NamePrincipal(username))
                .credential(simplePassword)
                .addSecurityProvider(new DbaasSecurityProvider(simplePassword));

        AgroalDataSource dataSource = AgroalDataSource.from(dataSourceConfiguration.get());

        AgroalDataSourceMetricsBinder metricsBinder = new AgroalDataSourceMetricsBinder(dataSource, List.of(Tag.of("datasource", "default")));
        metricsBinder.bindTo(Metrics.globalRegistry);
        return dataSource;
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

    public static void updatePassword(AgroalDataSource dataSource, String password) {
        AgroalSecurityProvider securityProvider = getSecurityProvider(dataSource);

        if (securityProvider instanceof JdbcUtils.DbaasSecurityProvider) {
            DbaasSecurityProvider dbaasSecurityProvider = (DbaasSecurityProvider) securityProvider;
            dbaasSecurityProvider.setCurrentDatabasePassword(new SimplePassword(password));
        } else {
            throw new IllegalStateException("Cannot update db password because DbaasSecurityProvider not enabled");
        }

    }

    private static class DbaasSecurityProvider extends AgroalDefaultSecurityProvider {
        private final Lock lock = new ReentrantLock();

        private SimplePassword currentDatabasePassword;

        public DbaasSecurityProvider(SimplePassword currentDatabasePassword) {
            this.currentDatabasePassword = currentDatabasePassword;
        }

        @Override
        public Properties getSecurityProperties(Object securityObject) {
            this.lock.lock();
            try {
                if (securityObject instanceof SimplePassword) {
                    return currentDatabasePassword.asProperties();
                }
            } finally {
                this.lock.unlock();
            }
            return super.getSecurityProperties(securityObject);
        }

        public void setCurrentDatabasePassword(SimplePassword currentDatabasePassword) {
            this.lock.lock();
            this.currentDatabasePassword = currentDatabasePassword;
            this.lock.unlock();
        }
    }

    public static AgroalSecurityProvider getSecurityProvider(AgroalDataSource dataSource) {
        return dataSource.getConfiguration()
                .connectionPoolConfiguration()
                .connectionFactoryConfiguration()
                .securityProviders()
                .stream()
                .findFirst()
                .get();
    }

}
