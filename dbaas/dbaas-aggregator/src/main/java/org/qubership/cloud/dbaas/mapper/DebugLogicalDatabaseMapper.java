package org.qubership.cloud.dbaas.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.cloud.dbaas.dto.v3.DebugDatabaseDeclarativeConfigV3;
import org.qubership.cloud.dbaas.dto.v3.DebugLogicalDatabaseV3;
import org.qubership.cloud.dbaas.entity.dto.DebugLogicalDatabasePersistenceDto;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.stream.Stream;

@Mapper
public abstract class DebugLogicalDatabaseMapper {

    protected final ObjectMapper objectMapper = new ObjectMapper();

    public abstract List<DebugLogicalDatabaseV3> convertDebugLogicalDatabases(List<DebugLogicalDatabasePersistenceDto> dtos);

    @Mapping(source = "logicalDatabaseBgVersion", target = "bgVersion")
    @Mapping(source = "logicalDatabaseType", target = "type")
    @Mapping(source = "logicalDatabaseConnectionProperties", target = "roles", qualifiedByName = "convertRoles")
    @Mapping(source = "logicalDatabaseName", target = "name")
    @Mapping(source = "logicalDatabasePhysicalDatabaseId", target = "physicalDbId")
    @Mapping(source = "externalAdapterRegistrationAddress", target = "physicalDbAdapterUrl")
    @Mapping(source = "dto" ,  target = "declaration")
    public abstract DebugLogicalDatabaseV3 convertDebugLogicalDatabase(DebugLogicalDatabasePersistenceDto dto);

    @Mapping(source = "databaseDeclarativeConfigId", target = "id")
    @Mapping(source = "databaseDeclarativeConfigSettings", target = "settings", qualifiedByName = "convertMap")
    @Mapping(source = "databaseDeclarativeConfigLazy", target = "lazy")
    @Mapping(source = "databaseDeclarativeConfigInstantiationApproach", target = "instantiationApproach")
    @Mapping(source = "databaseDeclarativeConfigVersioningApproach", target = "versioningApproach")
    @Mapping(source = "databaseDeclarativeConfigVersioningType", target = "versioningType")
    @Mapping(source = "databaseDeclarativeConfigClassifier", target = "classifier", qualifiedByName = "convertMap")
    @Mapping(source = "databaseDeclarativeConfigType", target = "type")
    @Mapping(source = "databaseDeclarativeConfigNamePrefix", target = "namePrefix")
    @Mapping(source = "databaseDeclarativeConfigNamespace", target = "namespace")
    public abstract DebugDatabaseDeclarativeConfigV3 convertDebugDatabaseDeclarativeConfig(DebugLogicalDatabasePersistenceDto dto);
    
    @AfterMapping
    @SuppressWarnings("unchecked")
    protected void afterMapping(@MappingTarget DebugLogicalDatabaseV3 debugLogicalDatabase,
                                DebugLogicalDatabasePersistenceDto dto) {
        var classifier = MapUtils.emptyIfNull(convertMap(dto.getLogicalDatabaseClassifier()));

        debugLogicalDatabase.setNamespace((String) classifier.get("namespace"));
        debugLogicalDatabase.setMicroservice((String) classifier.get("microserviceName"));
        debugLogicalDatabase.setTenantId((String) classifier.get("tenantId"));

        var logicalDbName = convertLogicalDbName(classifier);

        debugLogicalDatabase.setLogicalDbName(logicalDbName);
    }

    @SuppressWarnings("unchecked")
    protected String convertLogicalDbName(Map<String, Object> classifier) {
        var customKeysSnakeCase = MapUtils.emptyIfNull((Map<String, Object>) classifier.get("custom_keys"));
        var customKeysCamelCase = MapUtils.emptyIfNull((Map<String, Object>) classifier.get("customKeys"));

        return Stream.of(
                (String) customKeysSnakeCase.get("logicalDBName"),
                (String) customKeysSnakeCase.get("logicalDbName"),
                (String) customKeysSnakeCase.get("logicalDbId"),
                (String) customKeysCamelCase.get("logicalDBName"),
                (String) customKeysCamelCase.get("logicalDbName"),
                (String) customKeysCamelCase.get("logicalDbId")
            )
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
    }

    @Named("convertRoles")
    protected List<String> convertRoles(String connectionPropertiesStr) {
        if (StringUtils.isBlank(connectionPropertiesStr)) {
            return null;
        }

        try {
            var connectionProperties = objectMapper.readValue(
                connectionPropertiesStr,
                new TypeReference<List<Map<Object, Object>>>() {}
            );

            return connectionProperties.stream()
                .map(connectionProperty -> (String) connectionProperty.get("role"))
                .filter(Objects::nonNull)
                .toList();
        } catch (IOException e) {
            throw new RuntimeException("Error happened during converting roles", e);
        }
    }

    @Named("convertMap")
    protected SortedMap<String, Object> convertMap(String mapStr) {
        if (StringUtils.isBlank(mapStr)) {
            return null;
        }

        try {
            return objectMapper.readValue(mapStr, new TypeReference<>() {});
        } catch (IOException e) {
            throw new RuntimeException("Error happened during converting classifier", e);
        }
    }
}
