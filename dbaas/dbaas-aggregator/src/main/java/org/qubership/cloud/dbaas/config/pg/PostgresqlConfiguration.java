package org.qubership.cloud.dbaas.config.pg;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Dependent
public class PostgresqlConfiguration {

    @Produces
    @Singleton
    public PostgresqlMigrator dataSourceBeanPostProcessor(AgroalDataSource dataSource) {
        return new PostgresqlMigrator(dataSource);
    }

    @Produces
    @Singleton
    public PostgresqlDbStateMigrator dataSourceStateBeanPostProcessor(AgroalDataSource dataSource) {
        return new PostgresqlDbStateMigrator(dataSource);
    }

    @Produces
    @Singleton
    public PostgresqlClassifierMigration dataSourceClassifierMigrationPostProcessor(AgroalDataSource dataSource) {
        return new PostgresqlClassifierMigration(dataSource);
    }

}