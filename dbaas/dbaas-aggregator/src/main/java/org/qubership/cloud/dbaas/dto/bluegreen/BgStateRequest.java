package org.qubership.cloud.dbaas.dto.bluegreen;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.Nullable;
import lombok.Data;

import java.util.Date;
import java.util.Optional;


@Data
public class BgStateRequest {
    @JsonProperty("BGState")
    private BGState bGState;

    @Data
    public static class BGState {
        @JsonProperty(value = "controllerNamespace")
        @Nullable
        private String controllerNamespace;
        @JsonProperty("originNamespace")
        private BGStateNamespace originNamespace;
        @JsonProperty("peerNamespace")
        private BGStateNamespace peerNamespace;
        @JsonProperty("updateTime")
        private Date updateTime;

        public Optional<BGStateNamespace> getBgNamespaceWithState(String state){
            if (originNamespace.getState().equals(state)) {
                return Optional.of(originNamespace);
            }
            if (peerNamespace.getState().equals(state)) {
                return Optional.of(peerNamespace);
            }
            return Optional.empty();
        }
    }

    @Data
    public static class BGStateNamespace {
        private String name;
        private String state;
        private String version;
    }

}

