package org.qubership.cloud.dbaas.dto.v3;

import io.smallrye.jwt.auth.principal.DefaultJWTCallerPrincipal;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.ws.rs.core.SecurityContext;
import org.qubership.cloud.dbaas.dto.AbstractDatabaseCreateRequest;
import org.qubership.cloud.dbaas.entity.pg.DatabaseDeclarativeConfig;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.*;

import java.security.Principal;
import java.util.Map;

@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Data
@Schema(description = "V3 Request model for adding database to DBaaS")
@NoArgsConstructor
public class DatabaseCreateRequestV3 extends AbstractDatabaseCreateRequest implements UserRolesServices {
    public DatabaseCreateRequestV3(@NonNull Map<String, Object> classifier, @NonNull String type) {
        super(classifier, type);
    }

    @Schema(description = "Origin service which send request")
    private String originService;

    @Schema(description = "Indicates connection properties with which user role should be returned to a client")
    private String userRole;

    public DatabaseCreateRequestV3(DatabaseDeclarativeConfig databaseDeclarativeConfig, String originService, String userRole) {
        super.setClassifier(databaseDeclarativeConfig.getClassifier());
        super.setType(databaseDeclarativeConfig.getType());
        super.setBackupDisabled(false);
        super.setSettings(databaseDeclarativeConfig.getSettings());
        super.setNamePrefix(databaseDeclarativeConfig.getNamePrefix());
        this.originService = originService;
        this.userRole = userRole;
    }

    public String getOriginService() {
        if(originService != null && !originService.isEmpty()) {
            return originService;
        }

        SecurityContext securityContext = CDI.current().select(SecurityContext.class).get();
        Principal defaultPrincipal = securityContext.getUserPrincipal();

        if(!(defaultPrincipal instanceof DefaultJWTCallerPrincipal principal)) {
            return originService;
        }

        Map<String, Object> kubernetesClaims = principal.getClaim("kubernetes.io");

        JsonObject serviceAccount = (JsonObject) kubernetesClaims.get("serviceaccount");
        JsonString serviceAccountName = (JsonString)  serviceAccount.get("name");

        return serviceAccountName.getString();
    }
}
