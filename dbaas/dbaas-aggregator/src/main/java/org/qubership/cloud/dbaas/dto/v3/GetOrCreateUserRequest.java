package org.qubership.cloud.dbaas.dto.v3;

import org.eclipse.microprofile.openapi.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.util.SortedMap;

@Data
@AllArgsConstructor
@RequiredArgsConstructor
public class GetOrCreateUserRequest {
    @Schema(required = true, description = "Classifier describes the purpose of the database and distinguishes this database from other database in the same namespase. " +
            "It contains such keys as scope, microserviceName, namespace.")
    @NotNull
    private SortedMap<String, Object> classifier;

    @Schema(required = true, description = "User uniq identifier. Using this field with classifier and type dbaas will determine to create or return created before user.")
    @NotNull
    private String logicalUserId;

    @Schema(required = true, description = "The physical type of logical database. For example postgresql")
    @NotNull
    private String type;

    @Schema(required = false, description = "Identificator of physical database related to a logical database.")
    private String physicalDbId;

    @Schema(required = false, description = "Prefix for username")
    private String usernamePrefix;

    @Schema(required = false, description = "User role, for example admin, rw, ro")
    private String userRole;
}
