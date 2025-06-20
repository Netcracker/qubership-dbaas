package org.qubership.cloud.dbaas.dto.v3;

import org.qubership.cloud.dbaas.dto.AbstractDatabaseCreateRequest;
import org.qubership.cloud.dbaas.entity.pg.DatabaseDeclarativeConfig;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.*;

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

    @Schema(description = "Origin service which send request", required = true)
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
}
