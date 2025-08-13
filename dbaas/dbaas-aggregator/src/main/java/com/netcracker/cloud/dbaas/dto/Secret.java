package com.netcracker.cloud.dbaas.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class Secret {
    private String data;

    public Secret(String data) {
        this.data = data;
    }
}
