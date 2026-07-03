package com.netcracker.cloud.dbaas.dto.v3;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.netcracker.cloud.dbaas.entity.pg.DatabaseRegistry;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.SortedMap;

@Data
@NoArgsConstructor
@Schema(description = "A database whose credentials changed (password rotation or restore). " +
        "Carries only the identity needed to locate the consumer; connection properties are fetched separately.")
public class ChangedDatabaseResponse {

    @Schema(description = "Registry id. Together with lastRotatedAt it forms the keyset cursor the caller " +
            "echoes back as sinceId to page deterministically through rows that share a timestamp.")
    private String id;

    @Schema(description = "Namespace where the database is placed.")
    private String namespace;

    @Schema(description = "Classifier identifying the database within the namespace.")
    private SortedMap<String, Object> classifier;

    @Schema(description = "Type of database (e.g. postgresql, mongodb).")
    private String type;

    @Schema(description = "Timestamp of the credential change; also serves as the poll cursor.")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private OffsetDateTime lastRotatedAt;

    public ChangedDatabaseResponse(DatabaseRegistry registry) {
        this.id = registry.getId() != null ? registry.getId().toString() : null;
        this.namespace = registry.getNamespace();
        this.classifier = registry.getClassifier();
        this.type = registry.getType();
        // The marker lives on the shared Database, not per-classifier.
        this.lastRotatedAt = registry.getDatabase() != null ? registry.getDatabase().getLastRotatedAt() : null;
    }
}
