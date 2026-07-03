package com.netcracker.cloud.dbaas.dto.v3;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Page of databases whose credentials changed since the requested keyset cursor, " +
        "plus the current high-water mark used to seed the cursor.")
public class ChangedDatabasesResponse {

    @Schema(description = "Databases changed after the requested cursor, ordered by (lastRotatedAt, id) ascending. " +
            "Advance the cursor from the last item.")
    private List<ChangedDatabaseResponse> items;

    @Schema(description = "Latest (lastRotatedAt, id) currently known across all databases. Use it only to SEED " +
            "the cursor on the first (since-less) call; null when nothing has rotated yet.")
    private ChangedDatabasesCursor highWaterMark;
}
