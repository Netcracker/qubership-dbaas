package com.netcracker.it.dbaas.entity;

import lombok.Data;

@Data
public class BgDomainForList {
    private String controllerNamespace;
    private String originNamespace;
    private String peerNamespace;
}
