package org.qubership.cloud.dbaas.dto.bluegreen;

import jakarta.annotation.Nullable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BgNamespaceRequest {
    private String peerNamespace;
    private String originNamespace;
    @Nullable
    private String controllerNamespace;

    public BgNamespaceRequest(String peerNamespace, String originNamespace) {
        this.peerNamespace = peerNamespace;
        this.originNamespace = originNamespace;
    }
}