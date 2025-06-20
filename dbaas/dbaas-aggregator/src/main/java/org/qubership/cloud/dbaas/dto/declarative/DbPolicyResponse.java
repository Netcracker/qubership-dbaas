package org.qubership.cloud.dbaas.dto.declarative;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.qubership.cloud.dbaas.dto.conigs.ApplyConfigResponseComponent;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Data
public class DbPolicyResponse implements ApplyConfigResponseComponent {

    @JsonProperty("message")
    private String message;

    @JsonProperty("status")
    private String status;

}