package org.qubership.cloud.dbaas.utils;

import org.qubership.cloud.dbaas.DatabaseType;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class DbaasBackupUtils {

    public static String getDatabaseName(DatabaseRegistry databaseRegistry) {
        if (databaseRegistry == null) {
            return null;
        }

        return getDatabaseName(databaseRegistry.getDatabase());
    }

    public static String getDatabaseName(Database database) {
        if (database == null) {
            return null;
        }

        var databaseName = database.getName();

        if (StringUtils.isBlank(databaseName)) {
            var databaseRegistryType = extractStringValueFromFirstElementOfList(
                database.getDatabaseRegistry(), DatabaseRegistry::getType
            );

            if (DatabaseType.OPENSEARCH.name().equalsIgnoreCase(databaseRegistryType)) {

                var resourcePrefix = extractStringValueFromFirstElementOfList(
                    database.getConnectionProperties(),
                    connectionProperty -> (String) connectionProperty.get("resourcePrefix")
                );

                if (StringUtils.isNotBlank(resourcePrefix)) {
                    databaseName = resourcePrefix;
                }
            }
        }

        return databaseName;
    }

    private static <T> String extractStringValueFromFirstElementOfList(List<T> list, Function<T, String> extractStringValueFunction) {
        return Optional.ofNullable(list)
            .filter(databaseRegistries -> !databaseRegistries.isEmpty())
            .map(List::getFirst)
            .map(extractStringValueFunction)
            .orElse(null);
    }
}
