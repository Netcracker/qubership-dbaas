package org.qubership.cloud.dbaas.dto.v3;

import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Set;

@Data
@AllArgsConstructor
public class PermanentPerNamespaceRuleDeleteDTO {

    @Schema(
            required = false,
            description = "Db type for which rules should be deleted. If omitted all rules for specified namespaces will be deleted.")
    private String dbType;

    @Schema(
            required = true,
            description = "Namespaces for which rules should be deleted")
    private Set<String> namespaces;
}
