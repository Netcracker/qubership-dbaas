package com.netcracker.cloud.dbaas.config;

import com.github.kagkarlsson.scheduler.task.Task;
import com.netcracker.cloud.dbaas.repositories.dbaas.BalancingRulesDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.dbaas.DatabaseDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.dbaas.LogicalDbDbaasRepository;
import com.netcracker.cloud.dbaas.service.AsyncOperations;
import com.netcracker.cloud.dbaas.service.BalancingRulesService;
import com.netcracker.cloud.dbaas.service.PhysicalDatabasesService;
import com.netcracker.cloud.dbaas.service.dbsettings.DefaultDbSettingsHandler;
import com.netcracker.cloud.dbaas.service.dbsettings.LogicalDbSettingsService;
import com.netcracker.cloud.dbaas.service.dbsettings.PostgresqlSettingsHandler;
import com.netcracker.core.scheduler.po.ProcessOrchestrator;
import io.quarkus.arc.All;
import io.quarkus.arc.properties.UnlessBuildProperty;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.ExtensibleLockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.support.KeepAliveLockProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;

import static com.netcracker.cloud.dbaas.JdbcUtils.PROCESS_ORCHESTRATOR_DATASOURCE;

@Dependent
@Slf4j
public class ServicesConfig {

    public static final Object DBAAS_REPOSITORIES_MUTEX = new Object();

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
    public ExtensibleLockProvider lockProvider(@Named(PROCESS_ORCHESTRATOR_DATASOURCE) DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build()
        );
    }

    @Produces
    @Singleton
    @Typed(KeepAliveLockProvider.class)
    public KeepAliveLockProvider keepAliveLockProvider(ExtensibleLockProvider provider, AsyncOperations asyncOperations) {
        return new KeepAliveLockProvider(provider, asyncOperations.getHeartbeatExecutor());
    }
}
