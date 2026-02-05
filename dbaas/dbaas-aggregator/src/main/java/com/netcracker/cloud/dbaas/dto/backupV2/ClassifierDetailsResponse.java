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
            description = "Name of the source database. From which the classifier was obtained via backup",
            required = true,
            examples = {
                    "oldDb"
            }
    )
    private String previousDatabase;
    @Schema(
            description = "Final classifier used to create a database in the target environment. ",
            examples = "[{\"namespace\":\"namespace\", \"microserviceName\":\"microserviceName\", \"scope\":\"service\"}]",
            required = true
    )
    private SortedMap<String, Object> classifier;
    @Schema(
            description = "Classifier state before applying restore-side mapping. " +
                    "Represents classifier data coming from backup, adapted to restore model " +
                    "but not yet transformed",
            examples = "[{\"namespace\":\"namespace\", \"microserviceName\":\"microserviceName\", \"scope\":\"service\"}]",
            required = true
    )
    private SortedMap<String, Object> classifierBeforeMapper;
}
