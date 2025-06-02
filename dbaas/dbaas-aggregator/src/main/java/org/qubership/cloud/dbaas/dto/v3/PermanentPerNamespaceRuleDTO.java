package org.qubership.cloud.dbaas.dto.v3;

import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Set;

@Data
@AllArgsConstructor
@Schema(description = "Rule allows to define in which physical database new logical databases should be created")
public class PermanentPerNamespaceRuleDTO {

    @Schema(
           required = true,
            description = "Physical db type (ex. postgresql, mongodb, etc), for which rule should be applied.")
    private String dbType;

    @Schema(
           required = true,
            description = "Identifier of physical database where newly created logical databases should be placed.")
    private String physicalDatabaseId;

    @Schema(
           required = true,
            description = "Namespaces for which rules should be applied")
    private Set<String> namespaces;
}
