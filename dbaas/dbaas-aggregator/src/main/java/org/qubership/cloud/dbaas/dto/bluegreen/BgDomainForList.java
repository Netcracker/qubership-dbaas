package org.qubership.cloud.dbaas.dto.bluegreen;

import org.qubership.cloud.dbaas.entity.pg.BgDomain;
import org.qubership.cloud.dbaas.entity.pg.BgNamespace;
import lombok.Data;

import java.util.List;

@Data
public class BgDomainForList {
    private String originNamespace;
    private String peerNamespace;
    private String controllerNamespace;

    public BgDomainForList(BgDomain bgDomain) {
        this.controllerNamespace = bgDomain.getControllerNamespace();
        if (bgDomain.getPeerNamespace() == null || bgDomain.getOriginNamespace() == null) {
            List<BgNamespace> namespaces = bgDomain.getNamespaces();
            this.originNamespace = namespaces.get(0).getNamespace();
            this.peerNamespace = namespaces.get(1).getNamespace();
        } else {
            this.originNamespace = bgDomain.getOriginNamespace();
            this.peerNamespace = bgDomain.getPeerNamespace();
        }
    }
}
