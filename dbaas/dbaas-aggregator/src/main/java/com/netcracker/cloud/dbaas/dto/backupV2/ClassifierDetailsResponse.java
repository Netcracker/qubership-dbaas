package com.netcracker.cloud.dbaas.dto.backupV2;

import com.netcracker.cloud.dbaas.enums.ClassifierType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.SortedMap;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Classifier details used during restore operation")
public class ClassifierDetailsResponse {
    @Schema(
            description = "Type of classifier in restore context",
            required = true,
            implementation = ClassifierType.class
    )
    private ClassifierType type;

    @Schema(
            description = "Name of the existing database previously associated with this classifier," +
                    " used when the classifier replaces or transiently replaces another database during restore",
            nullable = true,
            examples = {"dbaas_12345"}
    )
    private String previousDatabase;

    @Schema(
            description = "Final classifier used to create a database in the target environment.",
            examples = "{\"namespace\":\"namespace\", \"microserviceName\":\"microserviceName\", \"scope\":\"service\"}",
            required = true
    )
    private SortedMap<String, Object> classifier;

    @Schema(
            description = "Original (pre-mapping) classifier from backup database preserved " +
                    "to track how mapping changed the classifier during restore",
            examples = "{\"namespace\":\"namespace\", \"microserviceName\":\"microserviceName\", \"scope\":\"service\"}",
            nullable = true
    )
    private SortedMap<String, Object> classifierBeforeMapper;
}
