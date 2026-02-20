package com.netcracker.cloud.dbaas.dto.v3;

import java.util.Map;

public interface UserRolesServices {
    String getOriginService();

    void setOriginService(String originService);

    String getUserRole();

    Map<String, Object> getClassifier();
}
