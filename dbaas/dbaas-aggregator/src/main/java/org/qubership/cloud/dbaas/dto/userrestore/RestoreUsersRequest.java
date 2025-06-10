package org.qubership.cloud.dbaas.dto.userrestore;

import org.eclipse.microprofile.openapi.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.SortedMap;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class RestoreUsersRequest {
    @Schema(required = true, description = "Classifier describes the purpose of the database and distinguishes this database from other database in the same namespase. " +
            "It contains such keys as scope, microserviceName, namespace.")
    @NotNull
    private SortedMap<String, Object> classifier;

    @Schema(required = true, description = "The physical type of logical database. For example postgresql")
    @NotNull
    private String type;

    @Schema(required = false, description = "User role")
    private String role;
}
