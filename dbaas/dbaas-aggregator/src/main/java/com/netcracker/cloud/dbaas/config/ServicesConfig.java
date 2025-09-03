package com.netcracker.cloud.dbaas.config;

import com.github.kagkarlsson.scheduler.task.Task;
import io.quarkus.arc.properties.UnlessBuildProperty;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import com.netcracker.cloud.dbaas.JdbcUtils;
import com.netcracker.cloud.dbaas.repositories.dbaas.BalancingRulesDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.dbaas.DatabaseDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.dbaas.LogicalDbDbaasRepository;
import com.netcracker.cloud.dbaas.service.BalancingRulesService;
import com.netcracker.cloud.dbaas.service.PhysicalDatabasesService;
import com.netcracker.cloud.dbaas.service.dbsettings.DefaultDbSettingsHandler;
import com.netcracker.cloud.dbaas.service.dbsettings.LogicalDbSettingsService;
import com.netcracker.cloud.dbaas.service.dbsettings.PostgresqlSettingsHandler;
import com.netcracker.core.scheduler.po.ProcessOrchestrator;
import io.quarkus.arc.All;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;

@Dependent
@Slf4j
public class ServicesConfig {

    public static final Object DBAAS_REPOSITORIES_MUTEX = new Object();

    private static final String PROCESS_ORCHESTRATOR_DATASOURCE = "po-datasource";

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

    @Produces
    @Singleton
    @Named(PROCESS_ORCHESTRATOR_DATASOURCE)
    public DataSource processOrchestratorDataSource() throws SQLException {
        return JdbcUtils.buildDataSource(pgHost, pgPort, pgDatabase, pgUser, pgPassword, maxPoolSize, null);
    }

    @Produces
    @Singleton
    public BalancingRulesService balancingRulesService(BalancingRulesDbaasRepository balancingRulesDbaasRepository,
                                                       PhysicalDatabasesService physicalDatabasesService,
                                                       DatabaseDbaasRepository databaseDbaasRepository,
                                                       @ConfigProperty(name = "dbaas.balancing.defaultPhysicalDatabasesDisabled", defaultValue = "false") boolean defaultPhysicalDatabasesDisabled) {
        return new BalancingRulesService(balancingRulesDbaasRepository, physicalDatabasesService, databaseDbaasRepository, defaultPhysicalDatabasesDisabled);
    }

    @Produces
    @Singleton
    @Startup
    @UnlessBuildProperty(name = "dbaas.process-orchestrator.enabled", stringValue = "false", enableIfMissing = true)
    public ProcessOrchestrator processOrchestrator(@Named(PROCESS_ORCHESTRATOR_DATASOURCE) DataSource dataSource, @All List<Task<?>> list) {
        log.info("Creating PO with tasks: {}", list);
        return new ProcessOrchestrator(dataSource, 10, list);
    }

    @Produces
    @Singleton
    public LogicalDbSettingsService logicalDbSettingsService(PhysicalDatabasesService physicalDatabasesService,
                                                             LogicalDbDbaasRepository logicalDbDbaasRepository) {
        DefaultDbSettingsHandler defaultDbSettingsHandler = new DefaultDbSettingsHandler(physicalDatabasesService, logicalDbDbaasRepository);
        PostgresqlSettingsHandler postgresqlSettingsHandler = new PostgresqlSettingsHandler(defaultDbSettingsHandler);
        return new LogicalDbSettingsService(List.of(defaultDbSettingsHandler, postgresqlSettingsHandler));
    }

    @Produces
    @Singleton
    public LockProvider lockProvider() throws SQLException {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(processOrchestratorDataSource()))
                        .usingDbTime()
                        .build()
        );
    }
}
