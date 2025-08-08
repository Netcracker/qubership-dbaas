package org.qubership.cloud.dbaas.dto.declarative;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;


@Data
public class DeclarativeDatabaseCreationRequest {

	@JsonProperty("apiVersion")
	String apiVersion;

	@JsonProperty("kind")
	String kind;

	@JsonProperty("declarations")
    List<DatabaseDeclaration> declarations;

}
