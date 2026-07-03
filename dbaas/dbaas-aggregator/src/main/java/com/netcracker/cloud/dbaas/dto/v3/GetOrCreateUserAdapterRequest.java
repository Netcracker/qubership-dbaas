package com.netcracker.cloud.dbaas.dto.v3;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

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
