package org.qubership.cloud.dbaas.dto.v3;

import java.util.Map;

public interface UserRolesServices {
    String getOriginService();

    String getUserRole();

    Map<String, Object> getClassifier();
}
