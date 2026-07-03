package com.netcracker.cloud.dbaas.dto.conigs;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.netcracker.cloud.dbaas.dto.declarative.DatabaseDeclaration;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public class DeclarativeDatabaseCreation extends DatabaseDeclaration {

    @JsonProperty("kind")
    String kind;

}
