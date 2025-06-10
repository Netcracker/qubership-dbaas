package org.qubership.cloud.dbaas.dto.declarative;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.qubership.cloud.dbaas.dto.conigs.ApplyConfigResponseComponent;
import lombok.Data;

import java.util.SortedMap;

@Data
public class SpecDeclarativeResponseItem implements ApplyConfigResponseComponent {

    @JsonProperty("instantiationApproach")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String instantiationApproach;

    @JsonProperty("versioningApproach")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String versioningApproach;

    @JsonProperty("lazy")
    private Boolean lazy;

    @JsonProperty("classifier")
    private SortedMap<String, Object> classifier;

    @JsonProperty("type")
    private String type;

    @JsonProperty("status")
    private String status;
}