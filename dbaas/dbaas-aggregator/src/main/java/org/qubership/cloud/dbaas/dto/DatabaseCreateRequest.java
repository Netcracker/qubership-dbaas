package org.qubership.cloud.dbaas.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;

import java.util.List;
import java.util.Map;

@Data
@Schema(description = "Request model for adding database to DBaaS")
@NoArgsConstructor
public class DatabaseCreateRequest extends AbstractDatabaseCreateRequest {

    public DatabaseCreateRequest(@NonNull Map<String, Object> classifier, @NonNull String type) {
        super(classifier, type);
    }

    @Schema(description = "This is a prefix of the database name. Prefix depends on the type of the database and it should be less than 27 characters if dbName is not specified.")
    private String namePrefix;

    @Schema(description = "This is the name of the database. Name should be unique. If a database having this name exists there will be a conflict. " +
            "If dbName is absent it will be generated")
    private String dbName;

    @Schema(description = "It is a username for a user which will be created together with the database. If the username is absent it will be generated.")
    private String username;

    @Schema(description = "This is password for a user, which will be created together with the database. If password is absent it will be generated")
    private String password;
    @Schema(description = "The list of identifiers of initial scripts which should be executed in the adapter. Deprecated in v1 and v2, will be removed in v3. For more info visit https://perch.qubership.org/display/CLOUDCORE/How+to+remove+initScriptIdentifiers+parameter")
    @Deprecated
    private List<String> initScriptIdentifiers;

    @Schema(description = "The list of roles which are related to this logical database. The external security service (e.g. DBaaA Agent) can perform a verification process on this field.")
    private List<String> dbOwnerRoles;

    @Schema(required = true, description = "Database owner name")
    private String dbOwner;
}
