package org.qubership.cloud.dbaas.dto.v3;

import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.util.Map;

@Data
@AllArgsConstructor
@RequiredArgsConstructor
public class GetOrCreateUserResponse {
    @Schema(required = true, description = "The user identificator.")
    private String userId;
    @Schema(required = true, description = "The information about connection to database. It contains such keys as url, authDbName, username, password, port, host." +
            "Setting keys depends on the database type.")
    private Map<String, Object> connectionProperties;

    public static class GetOrCreateUserAcceptedResponse {
        private final String message;
        private final GetOrCreateUserRequest requestBody;

        public GetOrCreateUserAcceptedResponse(String message, GetOrCreateUserRequest requestBody) {
            this.message = message;
            this.requestBody = requestBody;
        }
    }
}
