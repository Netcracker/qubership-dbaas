package org.qubership.cloud.dbaas.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.qubership.cloud.dbaas.dto.conigs.RolesRegistration;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NonNull;

@EqualsAndHashCode(callSuper = true)
@Data
public class RolesRegistrationRequest extends RolesRegistration {
    @JsonProperty("apiVersion")
    @NonNull
    String apiVersion;

    @JsonProperty("kind")
    String kind;
}
