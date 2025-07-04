package org.qubership.cloud.dbaas.test.data.provider.debug;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.cloud.dbaas.dto.v3.DebugDatabaseDeclarativeConfigV3;
import org.qubership.cloud.dbaas.dto.v3.DebugLogicalDatabaseV3;
import org.qubership.cloud.dbaas.entity.dto.DebugLogicalDatabasePersistenceDto;

import java.util.List;

public class DebugLogicalDatabaseTestDataProvider {

    public static final String ACTUAL_DEBUG_LOGICAL_DATABASE_PERSISTENCE_DTO_JSON = """
        {
            "logicalDatabaseClassifier": "{\\"scope\\": \\"service\\", \\"tenantId\\": \\"ce22b065-1e61-4076-99b1-e397b6da741b\\", \\"namespace\\": \\"dbaas-autotests\\", \\"custom_keys\\": {\\"logicalDBName\\": \\"configs\\"}, \\"microserviceName\\": \\"dbaas-declarative-service\\"}",
            "logicalDatabaseBgVersion": "2",
            "logicalDatabaseType": "opensearch",
            "logicalDatabaseConnectionProperties": "[{\\"role\\":\\"admin\\",\\"port\\":5432,\\"host\\":\\"pg-patroni.postgresql-dev\\",\\"name\\":\\"dbaas-declarative-service_dbaas-autotests_175104799071124\\",\\"url\\":\\"jdbc:postgresql://pg-patroni.postgresql-dev:5432/dbaas-declarative-service_dbaas-autotests_175104799071124\\",\\"username\\":\\"dbaas_506eecb3117f4425bd39f4ab3da2cfb2\\",\\"encryptedPassword\\":\\"{v2c}{AES}{DEFAULT_KEY}{5T/FJTH4tCGDpzMqOZ+pRbIuhDEHbVHArsAIZ54FeURYu+e0qD7xx2F5CSmZfy6A}\\"},{\\"role\\":\\"streaming\\",\\"port\\":5432,\\"host\\":\\"pg-patroni.postgresql-dev\\",\\"name\\":\\"dbaas-declarative-service_dbaas-autotests_175104799071124\\",\\"url\\":\\"jdbc:postgresql://pg-patroni.postgresql-dev:5432/dbaas-declarative-service_dbaas-autotests_175104799071124\\",\\"username\\":\\"dbaas_7f28aba3d7a14e609f3a3fcc11f3f69e\\",\\"encryptedPassword\\":\\"{v2c}{AES}{DEFAULT_KEY}{j1f48bA4gtQfEMfqLG4j2OYb+Vt5UDk/XSxBzNcy8GRYu+e0qD7xx2F5CSmZfy6A}\\"},{\\"role\\":\\"rw\\",\\"port\\":5432,\\"host\\":\\"pg-patroni.postgresql-dev\\",\\"name\\":\\"dbaas-declarative-service_dbaas-autotests_175104799071124\\",\\"url\\":\\"jdbc:postgresql://pg-patroni.postgresql-dev:5432/dbaas-declarative-service_dbaas-autotests_175104799071124\\",\\"username\\":\\"dbaas_596dbf6b4045477d88f8905bd7448687\\",\\"encryptedPassword\\":\\"{v2c}{AES}{DEFAULT_KEY}{sWyhrSjIv3JjQYJ4QC041pb/cp2epPuXA4K6E5XnkyVYu+e0qD7xx2F5CSmZfy6A}\\"},{\\"role\\":\\"ro\\",\\"port\\":5432,\\"host\\":\\"pg-patroni.postgresql-dev\\",\\"name\\":\\"dbaas-declarative-service_dbaas-autotests_175104799071124\\",\\"url\\":\\"jdbc:postgresql://pg-patroni.postgresql-dev:5432/dbaas-declarative-service_dbaas-autotests_175104799071124\\",\\"username\\":\\"dbaas_6b699109e2214a678752054fffc63d36\\",\\"encryptedPassword\\":\\"{v2c}{AES}{DEFAULT_KEY}{PaUKVlTdegjrAfwJ6TKRJjMs5QnFU1XLjIcIfO076YNYu+e0qD7xx2F5CSmZfy6A}\\"}]",
            "logicalDatabaseName": "dbaas-declarative-service_dbaas-autotests_175104799071124",
            "logicalDatabasePhysicalDatabaseId": "postgresql-dev:postgres",
            "externalAdapterRegistrationAddress": "http://dbaas-postgres-adapter.postgresql-dev:8080",
            "databaseDeclarativeConfigId": "4cb9e41a-e4b2-4529-98fc-392c72873c75",
            "databaseDeclarativeConfigSettings": "null",
            "databaseDeclarativeConfigLazy": false,
            "databaseDeclarativeConfigInstantiationApproach": "new",
            "databaseDeclarativeConfigVersioningApproach": "new",
            "databaseDeclarativeConfigVersioningType": "static",
            "databaseDeclarativeConfigClassifier": "{\\"scope\\": \\"service\\", \\"namespace\\": \\"dbaas-autotests\\", \\"custom_keys\\": {\\"logicalDBName\\": \\"configs\\"}, \\"microserviceName\\": \\"dbaas-declarative-service\\"}",
            "databaseDeclarativeConfigType": "postgresql",
            "databaseDeclarativeConfigNamePrefix": null,
            "databaseDeclarativeConfigNamespace": "dbaas-autotests"
        }
        """;

    public static final String EXPECTED_DEBUG_LOGICAL_DATABASE_JSON = """
        {
            "namespace": "dbaas-autotests",
            "microservice": "dbaas-declarative-service",
            "tenantId": "ce22b065-1e61-4076-99b1-e397b6da741b",
            "logicalDbName": "configs",
            "bgVersion": "2",
            "type": "opensearch",
            "roles": [
                "admin",
                "streaming",
                "rw",
                "ro"
            ],
            "name": "dbaas-declarative-service_dbaas-autotests_175104799071124",
            "physicalDbId": "postgresql-dev:postgres",
            "physicalDbAdapterUrl": "http://dbaas-postgres-adapter.postgresql-dev:8080",
            "declaration": {
                "id": "4cb9e41a-e4b2-4529-98fc-392c72873c75",
                "settings": null,
                "lazy": false,
                "instantiationApproach": "new",
                "versioningApproach": "new",
                "versioningType": "static",
                "classifier": {
                    "custom_keys": {
                        "logicalDBName": "configs"
                    },
                    "microserviceName": "dbaas-declarative-service",
                    "namespace": "dbaas-autotests",
                    "scope": "service"
                },
                "type": "postgresql",
                "namePrefix": null,
                "namespace": "dbaas-autotests"
            }
        }
        """;

    public static final String EXPECTED_DEBUG_DATABASE_DECLARATIVE_CONFIG_JSON = """
        {
            "id": "4cb9e41a-e4b2-4529-98fc-392c72873c75",
            "settings": null,
            "lazy": false,
            "instantiationApproach": "new",
            "versioningApproach": "new",
            "versioningType": "static",
            "classifier": {
                "custom_keys": {
                    "logicalDBName": "configs"
                },
                "microserviceName": "dbaas-declarative-service",
                "namespace": "dbaas-autotests",
                "scope": "service"
            },
            "type": "postgresql",
            "namePrefix": null,
            "namespace": "dbaas-autotests"
        }
        """;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public DebugLogicalDatabasePersistenceDto getActualDebugLogicalDatabasePersistenceDto() throws
        JsonProcessingException {
        return objectMapper.readValue(
            ACTUAL_DEBUG_LOGICAL_DATABASE_PERSISTENCE_DTO_JSON, new TypeReference<>() {}
        );
    }

    public List<DebugLogicalDatabasePersistenceDto> getActualDebugLogicalDatabasePersistenceDtos() throws JsonProcessingException {
        return List.of(getActualDebugLogicalDatabasePersistenceDto());
    }

    public DebugLogicalDatabaseV3 getExpectedDebugLogicalDatabase() throws JsonProcessingException {
        return objectMapper.readValue(
            EXPECTED_DEBUG_LOGICAL_DATABASE_JSON, new TypeReference<>() {}
        );
    }

    public List<DebugLogicalDatabaseV3> getExpectedDebugLogicalDatabases() throws JsonProcessingException {
        return List.of(getExpectedDebugLogicalDatabase());
    }

    public DebugDatabaseDeclarativeConfigV3 getExpectedDebugDatabaseDeclarativeConfig() throws JsonProcessingException {
        return objectMapper.readValue(
            EXPECTED_DEBUG_DATABASE_DECLARATIVE_CONFIG_JSON, new TypeReference<>() {}
        );
    }
}
