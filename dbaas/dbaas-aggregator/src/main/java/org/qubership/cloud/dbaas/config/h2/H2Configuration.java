package org.qubership.cloud.dbaas.config.h2;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.h2.tools.Server;

import java.sql.SQLException;

@ApplicationScoped
@Slf4j
public class H2Configuration {

    private String h2Url;
    private Server webServe;

    public H2Configuration(@ConfigProperty(name = "quarkus.datasource.h2.jdbc.url") String h2Url) {
        this.h2Url = h2Url;
    }

    void postConstruct(@Observes StartupEvent event) throws SQLException {
        String h2ConsolePort = System.getProperty("h2.console.port");
        if (StringUtils.isNotEmpty(h2ConsolePort) && webServe == null) {
            webServe = Server.createWebServer("-web", "-webAllowOthers", "-webPort", h2ConsolePort).start();
            log.info("h2 console enabled and is available by {} port", h2ConsolePort);
        }
    }

    @PreDestroy
    void destroy() {
        if (webServe != null) {
            webServe.stop();
            webServe = null;
        }
    }
}
