package org.qubership.cloud.dbaas.dto.v3;

import org.qubership.cloud.dbaas.converter.ListConverter;
import org.qubership.cloud.dbaas.entity.pg.DbResource;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.*;

import jakarta.persistence.Convert;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Schema(description = "V3 Additional roles model for sending registration to DBaaS")
@NoArgsConstructor(force = true)
@RequiredArgsConstructor
@EqualsAndHashCode
public class AdditionalRoles {
    @Schema(required = true, description = "Logical database Id")
    @NonNull
    private UUID id;

    @Schema(required = true, description = "Logical database name")
    @NonNull
    private String dbName;

    @Schema(required = true, description = "Logical databases connection properties")
    @Convert(converter = ListConverter.class)
    @NonNull
    private List<Map<String, Object>> connectionProperties;

    @Schema(required = true, description = "Logical databases resources")
    @Convert(converter = ListConverter.class)
    @NonNull
    private List<DbResource> resources;

}
