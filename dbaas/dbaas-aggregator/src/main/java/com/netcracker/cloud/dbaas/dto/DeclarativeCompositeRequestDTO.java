package org.qubership.cloud.dbaas.dto;

import org.qubership.cloud.dbaas.dto.declarative.DeclarativeDatabaseCreationRequest;
import lombok.Data;

@Data
public class DeclarativeCompositeRequestDTO {
    RolesRegistrationRequest rolesRegistrationRequest;
    DeclarativeDatabaseCreationRequest databaseCreationRequest;
}
