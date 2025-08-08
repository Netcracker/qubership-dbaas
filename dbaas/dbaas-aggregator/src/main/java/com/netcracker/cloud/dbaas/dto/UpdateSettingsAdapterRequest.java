package org.qubership.cloud.dbaas.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

@Data
@Schema(description = "Contains current and new settings")
public class UpdateSettingsAdapterRequest {
    @Schema(required = true, description = "All current database settings already applied to the target database.")
    private Map<String, Object> currentSettings;
    @Schema(required = true, description = "New version of all database settings to apply to the database. Must consist of all settings the target database must incorporate after this update request will be executed.")
    private Map<String, Object> newSettings;

}
