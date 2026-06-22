package com.netcracker.cloud.dbaas.testapp;

import com.netcracker.cloud.dbaas.client.EnableFlywayPostgresql;
import com.netcracker.cloud.dbaas.client.config.EnableServiceDbaasPostgresql;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring counterpart of go-test-app-service. A black-box Kubernetes consumer used by the
 * DBaaS integration tests to prove the mounted-secret end-to-end flow for the Spring
 * PostgreSQL client:
 *
 * <ol>
 *   <li>{@code InternalDatabase} provisions a PostgreSQL logical database.</li>
 *   <li>{@code DatabaseSecretClaim} creates a Secret with connectionProperties.json + metadata.json.</li>
 *   <li>The Helm chart mounts that Secret under {@code /etc/secrets/dbaas-secrets/<secretName>}.</li>
 *   <li>{@code @EnableServiceDbaasPostgresql} resolves the mounted Secret (no REST to dbaas), Flyway
 *       runs the migration, and the service performs DML.</li>
 * </ol>
 *
 * The mounted-secret feature itself lives in dbaas-client-base; this app only exercises it.
 */
@SpringBootApplication
@EnableServiceDbaasPostgresql
@EnableFlywayPostgresql
public class SpringTestAppServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringTestAppServiceApplication.class, args);
    }
}
