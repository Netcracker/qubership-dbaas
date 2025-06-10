package org.qubership.cloud.dbaas.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.Data;
import lombok.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@Schema(description = "Response model for recreate existing database API. The model contains successful and unsuccessful databases")
public class RecreateDatabaseResponse {
    @Schema(description = "The list contains successfully recreated databases.")
    private List<Recreated> successfully = new ArrayList<>();
    @Schema(description = "The list contains requests from which an error occurred during recreating. " +
            "For these requests databases were not recreated.")
    private List<NotRecreated> unsuccessfully = new ArrayList<>();

    public void dbRecreated(Map<String, Object> classifier, String type, DatabaseResponse newDb) {
        successfully.add(new Recreated(classifier, type, newDb));
    }

    public void dbNotRecreated(Map<String, Object> classifier, String type, String error) {
        unsuccessfully.add(new NotRecreated(type, classifier, error));
    }

    @Data
    public class Recreated {
        @NonNull
        @Schema(required = true, description = "Requested classifier")
        private Map<String, Object> classifier;
        @NonNull
        @Schema(required = true, description = "Requested physical type of logical database. For example mongodb or postgresql")
        private String type;
        @NonNull
        @Schema(required = true, description = "A recreated logical database. This database has the same classifier as a original " +
                "but connection properties are different (url, dbname, username, password)")
        private DatabaseResponse newDb;
    }

    @Data
    private class NotRecreated {
        @NonNull
        @Schema(required = true, description = "Requested physical type of logical database. For example mongodb or postgresql")
        private String type;
        @NonNull
        @Schema(required = true, description = "Requested classifier")
        private Map<String, Object> classifier;
        @NonNull
        @Schema(required = true, description = "Contains a message of error that occurred during recreating")
        private String error;
    }
}
