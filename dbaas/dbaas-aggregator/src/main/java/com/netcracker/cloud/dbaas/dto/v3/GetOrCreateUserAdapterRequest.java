package org.qubership.cloud.dbaas.dto.v3;

import lombok.*;

@EqualsAndHashCode
@ToString
@Data
@AllArgsConstructor
@NoArgsConstructor
public class GetOrCreateUserAdapterRequest {
    private String dbName;
    private String role;
    private String userNamePrefix;
}
