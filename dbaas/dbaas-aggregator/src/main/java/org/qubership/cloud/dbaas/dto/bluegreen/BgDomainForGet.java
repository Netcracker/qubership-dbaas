package org.qubership.cloud.dbaas.dto.bluegreen;

import org.qubership.cloud.dbaas.entity.pg.BgDomain;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class BgDomainForGet {
    private UUID id;
    private List<BgNamespace> namespaces;
    private String controllerNamespace;

    public BgDomainForGet(BgDomain bgDomain) {
        this.id = bgDomain.getId();
        this.controllerNamespace = bgDomain.getControllerNamespace();
        this.namespaces = bgDomain.getNamespaces().stream().map(BgNamespace::new).toList();
    }
}
