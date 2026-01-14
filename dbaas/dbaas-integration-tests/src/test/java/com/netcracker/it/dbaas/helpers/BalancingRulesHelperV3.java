package com.netcracker.it.dbaas.helpers;

import com.netcracker.it.dbaas.entity.PermanentPerNamespaceRuleDeleteDTO;
import com.netcracker.it.dbaas.entity.PhysicalDatabaseRegistrationResponseDTOV3;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class BalancingRulesHelperV3 {

    private final DbaasHelperV3 dbaasHelperV3;

    public Map.Entry<String, String> getUniqLabelsByDbType(String dbType) {
        try {
            Collection<PhysicalDatabaseRegistrationResponseDTOV3>
                databases = dbaasHelperV3.getRegisteredPhysicalDatabases(dbType, dbaasHelperV3.getClusterDbaAuthorization(), 200).getIdentified().values();
            for (PhysicalDatabaseRegistrationResponseDTOV3 database : databases) {
                for (Map.Entry<String, String> map : database.getLabels().entrySet()) {
                    if (databases.stream().filter(o -> o.getLabels().containsKey(map.getKey())).count() == 1) {
                        return map;
                    }
                }
            }
            return null;
        } catch (Exception e) {
            log.error("An error occurred while receiving registered physical databases. {}", e.getMessage());
            return null;
        }
    }

    public List<DBWithUniqLabel> getAllDBsWithUniqLabels(String[] dbTypes) {
        return Arrays.stream(dbTypes)
                .filter(dbaasHelperV3::hasAdapterOfType)
                .map(dbType -> new DBWithUniqLabel(dbType, this.getUniqLabelsByDbType(dbType)))
                .filter(dbWithUniqLabel -> dbWithUniqLabel.label != null)
                .toList();
    }

    public void deletePermanentRules(String namespace) throws IOException {
        var permanentRulesToBeDeleted = List.of(
            new PermanentPerNamespaceRuleDeleteDTO("", List.of(namespace))
        );

        Request cleanupRequest = dbaasHelperV3.createRequest(
            DbaasHelperV3.PERMANENT_RULES_V3,
            dbaasHelperV3.getClusterDbaAuthorization(),
            permanentRulesToBeDeleted,
            "DELETE"
        );

        Response response = dbaasHelperV3.executeRequest(cleanupRequest, 200);
        response.close();
    }

    @Data
    @AllArgsConstructor
    public static class DBWithUniqLabel {
        String dbType;
        Map.Entry<String, String> label;
    }
}
