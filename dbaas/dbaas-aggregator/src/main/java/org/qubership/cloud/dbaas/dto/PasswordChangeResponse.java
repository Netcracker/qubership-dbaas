package org.qubership.cloud.dbaas.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.Data;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Getter
@ToString
public class PasswordChangeResponse {

    @Schema(description = "List containing \"classifier:connection\" information with which the password was changed successfully.")
    private List<PasswordChanged> changed = new ArrayList<>();

    @Schema(description = "List containing fail information.")
    private List<PasswordFailed> failed = new ArrayList<>();

    @JsonIgnore
    private int failedHttpStatus = 0;

    public void putSuccessEntity(Map<String, Object> classifier, Map<String, Object> connection) {
        changed.add(new PasswordChanged(classifier, connection));
    }

    public void putFailedEntity(Map<String, Object> classifier, String message) {
        failed.add(new PasswordFailed(classifier, message));
    }

    public void setFailedHttpStatus(int failedHttpStatus) {
        this.failedHttpStatus = failedHttpStatus;
    }

    @Data
    public static class PasswordChanged {

        @NonNull
        @Schema(required = true, description = "Database composite identify key.")
        private Map<String, Object> classifier;

        @NonNull
        @Schema(required = true, description = "New database connection.")
        private Map<String, Object> connection;
    }

    @Data
    public static class PasswordFailed {

        @NonNull
        @Schema(required = true, description = "Database composite identify key.")
        private Map<String, Object> classifier;

        @NonNull
        @Schema(required = true, description = "Error message.")
        private String message;
    }
}




