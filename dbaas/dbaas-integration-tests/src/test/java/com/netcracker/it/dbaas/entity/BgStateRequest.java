package com.netcracker.it.dbaas.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.Nullable;
import lombok.Data;

import java.util.Date;

@Data
public class BgStateRequest {
    @JsonProperty("BGState")
    private BGState BGState;

    @Data
    public static class BGState {
        @Nullable
        private String controllerNamespace;
        private BGStateNamespace originNamespace;
        private BGStateNamespace peerNamespace;
        private Date updateTime;
    }

    @Data
    public static class BGStateNamespace {
        private String name;
        private String state;
        private String version;
    }
}
