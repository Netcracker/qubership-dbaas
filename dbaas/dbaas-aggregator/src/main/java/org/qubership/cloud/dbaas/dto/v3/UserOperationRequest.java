package org.qubership.cloud.dbaas.dto.v3;

import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.util.SortedMap;

@Data
@AllArgsConstructor
@RequiredArgsConstructor
public class UserOperationRequest {
    @Schema(required = false, description = "Classifier describes the purpose of the database and distinguishes this database from other database in the same namespase. " +
            "It contains such keys as dbClassifier, scope, microserviceName, namespace. Setting keys depends on the database type.")
    private SortedMap<String, Object> classifier;

    @Schema(required = false, description = "User uniq identifier. Using this field with classifier and type dbaas will determine to create or return created before user.")
    private String logicalUserId;

    @Schema(required = false, description = "The physical type of logical database. For example mongodb or postgresql")
    private String type;

    @Schema(required = false, description = "The user identificator.")
    private  String userId;
}
