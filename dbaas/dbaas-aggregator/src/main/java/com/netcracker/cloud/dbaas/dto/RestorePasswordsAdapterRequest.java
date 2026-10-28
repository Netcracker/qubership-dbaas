package com.netcracker.cloud.dbaas.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "Request for starting passwords restoration process")
public class RestorePasswordsAdapterRequest {
    @Schema(description = "Settings with trackId")
    Map<String, Object> settings;
    @Schema(description = "List with connection properties of all users which need restoration")
    List<Map<String, Object>> connectionProperties;

    public static final String EXAMPLE_REQUEST = """
            {
              "settings": { "trackId": "3fa85f64-5717-4562-b3fc-2c963f66afa6" },
              "connectionProperties": [
                { "username": "svc_inventory_ro", "password": "ChangeMe_Ro_123!" },
                { "username": "svc_inventory_rw", "password": "ChangeMe_Rw_123!" }
              ]
            }
            """;
}
