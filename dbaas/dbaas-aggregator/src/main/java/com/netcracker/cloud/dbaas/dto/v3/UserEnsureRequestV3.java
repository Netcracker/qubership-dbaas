package com.netcracker.cloud.dbaas.dto.v3;

import com.netcracker.cloud.dbaas.dto.UserEnsureRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "The request to ensure database user. Extends UserEnsureRequest with a required role.")
public class UserEnsureRequestV3 extends UserEnsureRequest {

    public UserEnsureRequestV3(String dbName, String password, String role) {
        super(dbName, password);
        this.role = role;
    }

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Database role name. For instance Admin, RW or RO role.")
    private String role;

    public static final String EXAMPLE_REQUEST = """
            {
              "dbName": "inventory",
              "password": "ChangeMe_123!",
              "role": "RW"
            }
            """;

}
