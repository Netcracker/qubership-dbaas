package com.netcracker.cloud.dbaas.dto.v3;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Page of databases whose credentials changed since the requested cursor, " +
        "plus the current high-water mark used to seed and advance the cursor.")
public class ChangedDatabasesResponse {

    @Schema(description = "Databases changed after the requested cursor, ordered by lastRotatedAt ascending.")
    private List<ChangedDatabaseResponse> items;

    @Schema(description = "Largest lastRotatedAt currently known across all databases. Use it only to SEED the cursor " +
            "on the first (since-less) call; on subsequent calls advance the cursor from the returned items, not from " +
            "this value (otherwise a full page would skip the tail). Null when nothing has rotated yet.")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private OffsetDateTime highWaterMark;
}
