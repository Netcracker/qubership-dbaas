package com.netcracker.cloud.dbaas.dto.v3;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.OffsetDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Keyset cursor (lastRotatedAt, id) for the changed-databases feed. Seed the poll cursor " +
        "from the high-water mark on the first (since-less) call; thereafter advance the cursor from the " +
        "returned items.")
public class ChangedDatabasesCursor {

    @Schema(description = "lastRotatedAt component of the cursor.")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private OffsetDateTime lastRotatedAt;

    @Schema(description = "Registry id component of the cursor (tie-breaker for equal timestamps).")
    private String id;
}
