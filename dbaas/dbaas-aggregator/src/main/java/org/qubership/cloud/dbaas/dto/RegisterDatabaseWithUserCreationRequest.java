package org.qubership.cloud.dbaas.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.Data;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;
import java.util.SortedMap;

@Schema(description = "Request to add database to registration")
@Data
public class RegisterDatabaseWithUserCreationRequest {

    @Schema(required = true, description = "Classifier describes the purpose of database, it should not exist in registration, or the request would be ignored.")
    @NotNull
    private SortedMap<String, Object> classifier;

    @Schema(required = true, description = "The type of database, for example postgresql or mongodb.")
    @NotNull
    private String type;

    @Schema(required = true, description = "Name of database.")
    @NotNull
    private String name;

    @Schema(description = "This parameter specifies if the DbaaS should except this database from backup/restore procedure. " +
            "The parameter cannot be modified and it is installed only once during registration request.")
    private Boolean backupDisabled = false;

    @Schema(description = "Physical database identifier where the registered database " +
            "should be located. If it is absent, adapter id may be used to identify the target physical database.")
    @Nullable
    private String physicalDatabaseId;

    @Schema(description = "Physical database host where the registered database is located. Must be in format: <service-name>.<namespace>, e.g.: pg-patroni.postgresql-core")
    @Nullable
    private String dbHost;
}
