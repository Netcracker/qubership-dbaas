package org.qubership.cloud.dbaas.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.qubership.cloud.dbaas.test.data.provider.debug.DebugLogicalDatabaseTestDataProvider;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class DebugLogicalDatabaseMapperTest {

    private final DebugLogicalDatabaseTestDataProvider testDataProvider =
        new DebugLogicalDatabaseTestDataProvider();

    private final DebugLogicalDatabaseMapper debugLogicalDatabaseMapper =
        Mappers.getMapper(DebugLogicalDatabaseMapper.class);

    @Test
    void testConvertDebugLogicalDatabases() throws JsonProcessingException {
        var expectedDebugLogicalDatabases = testDataProvider.getExpectedDebugLogicalDatabases();
        var actualDebugLogicalDatabasePersistenceDtos = testDataProvider.getActualDebugLogicalDatabasePersistenceDtos();

        var actualDebugLogicalDatabases = debugLogicalDatabaseMapper.convertDebugLogicalDatabases(
            actualDebugLogicalDatabasePersistenceDtos
        );

        Assertions.assertEquals(
            debugLogicalDatabaseMapper.objectMapper.valueToTree(expectedDebugLogicalDatabases),
            debugLogicalDatabaseMapper.objectMapper.valueToTree(actualDebugLogicalDatabases)
        );
    }

    @Test
    void testConvertDebugLogicalDatabase() throws JsonProcessingException {
        var expectedDebugLogicalDatabase = testDataProvider.getExpectedDebugLogicalDatabase();
        var actualDebugLogicalDatabasePersistenceDto = testDataProvider.getActualDebugLogicalDatabasePersistenceDto();

        var actualDebugLogicalDatabase = debugLogicalDatabaseMapper.convertDebugLogicalDatabase(
            actualDebugLogicalDatabasePersistenceDto
        );

        Assertions.assertEquals(
            debugLogicalDatabaseMapper.objectMapper.valueToTree(expectedDebugLogicalDatabase),
            debugLogicalDatabaseMapper.objectMapper.valueToTree(actualDebugLogicalDatabase)
        );
    }

    @Test
    void testConvertDebugDatabaseDeclarativeConfig() throws JsonProcessingException {
        var expectedDebugDatabaseDeclarativeConfig = testDataProvider.getExpectedDebugDatabaseDeclarativeConfig();
        var actualDebugDatabaseDeclarativeConfigPersistenceDto = testDataProvider.getActualDebugLogicalDatabasePersistenceDto();

        var actualDebugDatabaseDeclarativeConfig = debugLogicalDatabaseMapper.convertDebugDatabaseDeclarativeConfig(
            actualDebugDatabaseDeclarativeConfigPersistenceDto
        );

        Assertions.assertEquals(
            debugLogicalDatabaseMapper.objectMapper.valueToTree(expectedDebugDatabaseDeclarativeConfig),
            debugLogicalDatabaseMapper.objectMapper.valueToTree(actualDebugDatabaseDeclarativeConfig)
        );
    }
}
