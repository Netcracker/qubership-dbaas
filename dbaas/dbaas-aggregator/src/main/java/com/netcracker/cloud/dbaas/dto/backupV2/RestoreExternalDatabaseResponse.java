package com.netcracker.cloud.dbaas.dto.backupV2;

import com.netcracker.cloud.dbaas.entity.pg.backupV2.Classifier;
import lombok.Data;
import lombok.NoArgsConstructor;
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
            description = "List of database classifiers. Each classifier is a sorted map of attributes.",
            examples = "[{\"namespace\":\"namespace\", \"microserviceName\":\"microserviceName\", \"scope\":\"service\"}]"
    )
    private List<ClassifierResponse> classifiers;
}

