package org.qubership.cloud.dbaas.dto.v3;

import org.qubership.cloud.dbaas.dto.UserEnsureRequest;
import lombok.*;

@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UserEnsureRequestV3 extends UserEnsureRequest {
    public UserEnsureRequestV3(String dbName, String password, String role) {
        super(dbName, password);
        this.role = role;
    }

    private String role;
}
