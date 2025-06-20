package org.qubership.cloud.dbaas.dto.conigs;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.qubership.cloud.dbaas.dto.declarative.DatabaseDeclaration;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public class DeclarativeDatabaseCreation extends DatabaseDeclaration {

    @JsonProperty("kind")
    String kind;

}