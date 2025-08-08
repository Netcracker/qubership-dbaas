package org.qubership.cloud.dbaas.dto.v3;

import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.SortedMap;

@EqualsAndHashCode
@Data
@Schema(description = "Contains primary or source (\"from\") classifier by which a database record will be found and changed to target classifier (\"from\")")
public class UpdateClassifierRequestV3 {
    @Schema(required = true, description = "Primary or source classifier.")
    SortedMap<String, Object> from;
    @Schema(required = true, description = "Target classifier.")
    SortedMap<String, Object> to;
    @Schema(description = "Target classifier.", example = "false")
    boolean fromV1orV2ToV3 = false;
    @Schema(description = "Create copy of record database in dbaas.", example = "false")
    boolean clone = false;
}
