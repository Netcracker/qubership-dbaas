package com.netcracker.cloud.dbaas.dto;

import com.netcracker.cloud.dbaas.dto.declarative.DeclarativeDatabaseCreationRequest;
import lombok.Data;

@Data
public class DeclarativeCompositeRequestDTO {
    RolesRegistrationRequest rolesRegistrationRequest;
    DeclarativeDatabaseCreationRequest databaseCreationRequest;
}
