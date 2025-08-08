package org.qubership.cloud.dbaas.dto.bluegreen;

import lombok.Data;

import java.util.Date;
import java.util.UUID;

@Data
public class BgNamespace {
    private String namespace;
    private String state;
    private String version;
    private Date updateTime;
    private UUID bgDomain;

    public BgNamespace(org.qubership.cloud.dbaas.entity.pg.BgNamespace bgNamespace) {
        this.namespace = bgNamespace.getNamespace();
        this.state = bgNamespace.getState();
        this.version = bgNamespace.getVersion();
        this.updateTime = bgNamespace.getUpdateTime();
        this.bgDomain = bgNamespace.getBgDomain().getId();
    }
}
