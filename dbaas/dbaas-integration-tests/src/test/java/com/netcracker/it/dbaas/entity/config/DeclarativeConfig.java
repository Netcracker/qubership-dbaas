package com.netcracker.it.dbaas.entity.config;

public interface DeclarativeConfig {
    DeclarativePayload asPayload(String namespace, String serviceName);
}
