package org.qubership.cloud.dbaas.dto.v3;

import io.smallrye.jwt.auth.principal.DefaultJWTCallerPrincipal;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.ws.rs.core.SecurityContext;
import lombok.*;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.qubership.cloud.dbaas.dto.AbstractDatabaseCreateRequest;
import org.qubership.cloud.dbaas.entity.pg.DatabaseDeclarativeConfig;

import java.security.Principal;
import java.util.Map;

@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Data
@Schema(description = "V3 Request model for adding database to DBaaS")
@NoArgsConstructor
public class DatabaseCreateRequestV3 extends AbstractDatabaseCreateRequest implements UserRolesServices {
    @Schema(description = "Origin service which send request")
    private String originService;
    @Schema(description = "Indicates connection properties with which user role should be returned to a client")
    private String userRole;

    public DatabaseCreateRequestV3(@NonNull Map<String, Object> classifier, @NonNull String type) {
        super(classifier, type);
    }

    public DatabaseCreateRequestV3(DatabaseDeclarativeConfig databaseDeclarativeConfig, String originService, String userRole) {
        super.setClassifier(databaseDeclarativeConfig.getClassifier());
        super.setType(databaseDeclarativeConfig.getType());
        super.setBackupDisabled(false);
        super.setSettings(databaseDeclarativeConfig.getSettings());
        super.setNamePrefix(databaseDeclarativeConfig.getNamePrefix());
        this.originService = originService;
        this.userRole = userRole;
    }
}
