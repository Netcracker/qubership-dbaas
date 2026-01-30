package com.netcracker.cloud.dbaas.dto.backupV2;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Data
@NoArgsConstructor
@Schema(description = "External database details")
public class RestoreExternalDatabaseResponse {
    @Schema(description = "Name of the external database", examples = "mydb", required = true)
    private String name;
    @Schema(description = "Type of the database", examples = "postgresql")
    private String type;
    @Schema(
            description = "List of classifier objects describing database attributes.",
            implementation = ClassifierResponse.class,
            type = SchemaType.ARRAY
    )
    private List<ClassifierResponse> classifiers;
}

