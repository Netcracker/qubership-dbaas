package org.qubership.cloud.dbaas.config.pg;

import org.flywaydb.core.api.exception.FlywayValidateException;
import org.qubership.cloud.dbaas.JdbcUtils;
import io.agroal.api.AgroalDataSource;
import io.agroal.narayana.NarayanaTransactionIntegration;
import io.quarkus.agroal.runtime.*;
import io.quarkus.datasource.common.runtime.DatabaseKind;
import io.quarkus.datasource.runtime.DataSourceSupport;
import io.quarkus.datasource.runtime.DataSourcesBuildTimeConfig;
import io.quarkus.datasource.runtime.DataSourcesRuntimeConfig;
import io.quarkus.narayana.jta.runtime.TransactionManagerConfiguration;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Singleton;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.TransactionSynchronizationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.flywaydb.core.Flyway;
import org.jboss.tm.XAResourceRecoveryRegistry;

import java.sql.SQLException;

@Slf4j
@Alternative
@Priority(1)
@Singleton
public class PostgresqlDataSourceProducer extends DataSources {

    private static final String DEFAULT_DATASOURCE_NAME = "<default>";

    @ConfigProperty(name = "postgresql.host")
    String pgHost;

    @ConfigProperty(name = "postgresql.port")
    Integer pgPort;

    @ConfigProperty(name = "postgresql.database")
    String pgDatabase;

    @ConfigProperty(name = "postgresql.user")
    String pgUser;

    @ConfigProperty(name = "postgresql.password")
    String pgPassword;

    @ConfigProperty(name = "dbaas.datasource.maximum-pool-size", defaultValue = "15")
    Integer maxPoolSize;

    private final AgroalDataSourceSupport agroalDataSourceSupport;
    private TransactionManager transactionManager;
    private TransactionSynchronizationRegistry transactionSynchronizationRegistry;

    public PostgresqlDataSourceProducer(DataSourcesBuildTimeConfig dataSourcesBuildTimeConfig,
                                        DataSourcesRuntimeConfig dataSourcesRuntimeConfig,
                                        DataSourcesJdbcBuildTimeConfig dataSourcesJdbcBuildTimeConfig,
                                        DataSourcesJdbcRuntimeConfig dataSourcesJdbcRuntimeConfig,
                                        TransactionManagerConfiguration transactionRuntimeConfig,
                                        TransactionManager transactionManager,
                                        XAResourceRecoveryRegistry xaResourceRecoveryRegistry,
                                        TransactionSynchronizationRegistry transactionSynchronizationRegistry,
                                        DataSourceSupport dataSourceSupport,
                                        AgroalDataSourceSupport agroalDataSourceSupport,
                                        Instance<io.agroal.api.AgroalPoolInterceptor> agroalPoolInterceptors,
                                        Instance<AgroalOpenTelemetryWrapper> agroalOpenTelemetryWrapper) {

        super(dataSourcesBuildTimeConfig, dataSourcesRuntimeConfig, dataSourcesJdbcBuildTimeConfig, dataSourcesJdbcRuntimeConfig, transactionRuntimeConfig, transactionManager, xaResourceRecoveryRegistry, transactionSynchronizationRegistry, dataSourceSupport, agroalDataSourceSupport, agroalPoolInterceptors, agroalOpenTelemetryWrapper);
        this.agroalDataSourceSupport = agroalDataSourceSupport;
        this.transactionManager = transactionManager;
        this.transactionSynchronizationRegistry = transactionSynchronizationRegistry;
    }

    @Override
    public AgroalDataSource getDataSource(String dataSourceName) {
        AgroalDataSourceSupport.Entry entry = agroalDataSourceSupport.entries.get(dataSourceName);
        if (DatabaseKind.POSTGRESQL.equals(entry.resolvedDbKind)) {
            if (DEFAULT_DATASOURCE_NAME.equals(dataSourceName)) {
                AgroalDataSource dataSource;
                try {
                    dataSource = JdbcUtils.buildDataSource(pgHost, pgPort, pgDatabase, pgUser, pgPassword, maxPoolSize, new NarayanaTransactionIntegration(transactionManager, transactionSynchronizationRegistry));
                } catch (SQLException e) {
                    log.error("Failed to create datasource", e);
                    throw new RuntimeException(e);
                }
                Flyway flyway = Flyway.configure()
                        .dataSource(dataSource)
                        .baselineOnMigrate(true)
                        .locations("classpath:db/migration/postgresql")
                        .load();
                try {
                    flyway.migrate();
                } catch (FlywayValidateException e) {
                    log.error("Flyway migration failed, try to repair", e);
                    flyway.repair();
                    flyway.migrate();
                }
                return dataSource;
            }
        }
        return super.getDataSource(dataSourceName);
    }
}
