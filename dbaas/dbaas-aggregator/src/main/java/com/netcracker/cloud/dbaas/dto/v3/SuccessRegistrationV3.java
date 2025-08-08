package org.qubership.cloud.dbaas.dto.v3;

import org.qubership.cloud.dbaas.entity.pg.DbResource;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Schema(description = "V3 Request model for registration new physical databases roles ")
@NoArgsConstructor
@RequiredArgsConstructor
@EqualsAndHashCode
public class SuccessRegistrationV3 {
    @Schema(required = true, description = "Record Id")
    @NonNull
    private UUID id;

    @Schema(required = true, description = "Additional roles")
    @NonNull
    private List<Map<String,Object>> connectionProperties;

    @Schema(required = true, description = "Resources")
    @NonNull
    private List<DbResource> resources;

}
