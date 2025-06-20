package org.qubership.cloud.dbaas.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.cloud.dbaas.dto.HttpBasicCredentials;
import org.qubership.cloud.dbaas.dto.role.Role;
import org.qubership.cloud.dbaas.dto.v3.DatabaseResponseV3ListCP;
import org.qubership.cloud.dbaas.dto.v3.UpdateHostRequest;
import org.qubership.cloud.dbaas.entity.pg.*;
import org.qubership.cloud.dbaas.integration.config.PostgresqlContainerResource;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import org.qubership.cloud.dbaas.repositories.dbaas.PhysicalDatabaseDbaasRepository;
import org.qubership.cloud.dbaas.service.PasswordEncryption;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.MediaType;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.qubership.cloud.dbaas.Constants.ROLE;
import static org.qubership.cloud.dbaas.DbaasApiPath.DBAAS_PATH_V3;
import static org.qubership.cloud.dbaas.DbaasApiPath.VERSION_2;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
@Slf4j
class DbaasOperationTest {

    private static final String POSTGRESQL = "postgresql";

    @Inject
    DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;

    @Inject
    PhysicalDatabaseDbaasRepository physicalDatabaseDbaasRepository;

    @InjectSpy
    PasswordEncryption passwordEncryption;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    public void setUp() {
        clean();
    }

    @AfterEach
    public void tearDown() {
        clean();
    }

    @Transactional
    public void clean() {
        databaseRegistryDbaasRepository.findAllDatabaseRegistersAnyLogType().forEach(dbr -> databaseRegistryDbaasRepository.delete(dbr));
        physicalDatabaseDbaasRepository.findAll().forEach(pd -> physicalDatabaseDbaasRepository.delete(pd));
    }

    @Test
    void testSaveAndDeleteDatabase() throws JsonProcessingException {
        DatabaseRegistry databaseRegistry = createDatabase();
        QuarkusTransaction.requiringNew().run(() -> {
            databaseRegistryDbaasRepository.saveAnyTypeLogDb(databaseRegistry);
        });
        Optional<DatabaseRegistry> founded_dbr = databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(databaseRegistry.getClassifier(), POSTGRESQL);
        Assertions.assertFalse(founded_dbr.isEmpty());
        log.info("dbr {}", founded_dbr.get());
        List<DatabaseRegistry> registersAnyLogType = databaseRegistryDbaasRepository.findAllDatabaseRegistersAnyLogType();
        assertEquals(1, registersAnyLogType.size());

        PhysicalDatabase physicalDatabase = createPhysicalDatabase();
        QuarkusTransaction.requiringNew().run(() -> physicalDatabaseDbaasRepository.save(physicalDatabase));
        doReturn(UUID.randomUUID().toString()).when(passwordEncryption).decrypt(anyString());
        UpdateHostRequest updateHostRequest = new UpdateHostRequest();
        updateHostRequest.setClassifier(getClassifier());
        updateHostRequest.setType(POSTGRESQL);
        updateHostRequest.setMakeCopy(true);
        updateHostRequest.setPhysicalDatabaseId("destination-physical-id");
        updateHostRequest.setPhysicalDatabaseHost("pg-patroni.destination-pg");

        Response response = given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody(List.of(updateHostRequest)))
                .accept(MediaType.APPLICATION_JSON)
                .post(DBAAS_PATH_V3 + "/databases/update-host");

        assertEquals(200, response.getStatusCode());
        List<DatabaseResponseV3ListCP> databaseResponseV3ListCPS = objectMapper.readValue(response.getBody().print(), new TypeReference<List<DatabaseResponseV3ListCP>>() {
        });
        assertEquals(1, databaseResponseV3ListCPS.size());
        assertEquals("pg-patroni.destination-pg",
                databaseResponseV3ListCPS.getFirst().getConnectionProperties().getFirst().get("host"));
        assertEquals("jdbc:postgresql://pg-patroni.destination-pg:5432/dbaas_d11b5fd935e548a6bf8574d35db45555",
                databaseResponseV3ListCPS.getFirst().getConnectionProperties().getFirst().get("url"));
        assertEquals("destination-physical-id",
                databaseResponseV3ListCPS.getFirst().getPhysicalDatabaseId());
        assertEquals(getClassifier(), databaseResponseV3ListCPS.getFirst().getClassifier());

        registersAnyLogType = databaseRegistryDbaasRepository.findAllDatabaseRegistersAnyLogType();
        assertEquals(2, registersAnyLogType.size());
        log.info("dbr after update {}", registersAnyLogType);

    }

    @Test
    void testSaveAndDeleteDatabase2() {
        DatabaseRegistry databaseRegistry = createDatabase();
        QuarkusTransaction.requiringNew().run(() -> {
            databaseRegistryDbaasRepository.saveAnyTypeLogDb(databaseRegistry);
        });
        Optional<DatabaseRegistry> founded_dbr = databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(databaseRegistry.getClassifier(), POSTGRESQL);
        Assertions.assertFalse(founded_dbr.isEmpty());
        log.info("dbr {}", founded_dbr.get());
        List<DatabaseRegistry> registersAnyLogType = databaseRegistryDbaasRepository.findAllDatabaseRegistersAnyLogType();
        assertEquals(1, registersAnyLogType.size());

        PhysicalDatabase physicalDatabase = createPhysicalDatabase();
        QuarkusTransaction.requiringNew().run(() -> physicalDatabaseDbaasRepository.save(physicalDatabase));
        doReturn(UUID.randomUUID().toString()).when(passwordEncryption).decrypt(anyString());
        UpdateHostRequest updateHostRequest = new UpdateHostRequest();
        updateHostRequest.setClassifier(getClassifier());
        updateHostRequest.setType(POSTGRESQL);
        updateHostRequest.setMakeCopy(false);
        updateHostRequest.setPhysicalDatabaseId("destination-physical-id");
        updateHostRequest.setPhysicalDatabaseHost("pg-patroni.destination-pg");

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody(List.of(updateHostRequest)))
                .accept(MediaType.APPLICATION_JSON)
                .post(DBAAS_PATH_V3 + "/databases/update-host")
                .then()
                .statusCode(200);

        registersAnyLogType = databaseRegistryDbaasRepository.findAllDatabaseRegistersAnyLogType();
        assertEquals(1, registersAnyLogType.size());
        log.info("dbr after update {}", registersAnyLogType);
    }


    private DatabaseRegistry createDatabase() {
        Database database = new Database();
        database.setId(UUID.randomUUID());
        SortedMap<String, Object> classifier = getClassifier();
        database.setClassifier(classifier);
        database.setType(POSTGRESQL);
        database.setNamespace((String) classifier.get("namespace"));
        database.setConnectionProperties(Arrays.asList(new HashMap<String, Object>() {{
            put(ROLE, Role.ADMIN.toString());
            put("port", 5432);
            put("host", "pg-patroni.core-postgresql");
            put("name", "dbaas_d11b5fd935e548a6bf8574d35db45555");
            put("url", "jdbc:postgresql://pg-patroni.core-postgresql:5432/dbaas_d11b5fd935e548a6bf8574d35db45555");
            put("username", "dbaas_62f293eec4484af1a89171c5da70e769");
            put("encryptedPassword", "{v2c}{AES}{DEFAULT_KEY}{BmJWW/qsyfgN7EisgfjOaLHc+EOs7S1MYwB87sv4325/L/zojG7u7RDUjFa3K9t/}");
        }}));

        ArrayList<DatabaseRegistry> databaseRegistries = new ArrayList<>();
        DatabaseRegistry databaseRegistry = createDatabaseRegistry();
        databaseRegistry.setDatabase(database);
        databaseRegistries.add(databaseRegistry);
        database.setDatabaseRegistry(databaseRegistries);

        DbResource resource = new DbResource("someKind", "someName");
        List<DbResource> resources = new ArrayList<>();
        resources.add(resource);
        database.setResources(resources);
        database.setName("dbaas_d11b5fd935e548a6bf8574d35db45555");
        database.setAdapterId("c53854b3-d3f2-4b4d-b0b4-bde49b70bcc0");
        database.setPhysicalDatabaseId("core-postgresql");
        database.setDbState(new DbState(DbState.DatabaseStateStatus.CREATED));
        return databaseRegistry;
    }

    @NotNull
    private static SortedMap<String, Object> getClassifier() {
        SortedMap<String, Object> classifier = new TreeMap<>();
        classifier.put("microserviceName", "test-service");
        classifier.put("namespace", "test-namespace");
        classifier.put("scope", "service");
        return classifier;
    }


    private DatabaseRegistry createDatabaseRegistry() {
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        SortedMap<String, Object> classifier = getClassifier();
        databaseRegistry.setClassifier(classifier);
        databaseRegistry.setType(POSTGRESQL);
        databaseRegistry.setNamespace((String) classifier.get("namespace"));
        return databaseRegistry;
    }

    @SneakyThrows
    private String requestBody(Object content) {

        return objectMapper.writeValueAsString(content);
    }

    private PhysicalDatabase createPhysicalDatabase() {
        PhysicalDatabase db = new PhysicalDatabase();
        db.setId(UUID.randomUUID().toString());
        db.setPhysicalDatabaseIdentifier("destination-physical-id");
        db.setType(POSTGRESQL);
        ExternalAdapterRegistrationEntry adapterRegistrationEntry = new ExternalAdapterRegistrationEntry();
        adapterRegistrationEntry.setAdapterId("destination-adapterId");
        adapterRegistrationEntry.setAddress("http://destination-host:8080");
        adapterRegistrationEntry.setSupportedVersion(VERSION_2);
        adapterRegistrationEntry.setHttpBasicCredentials(new HttpBasicCredentials("user", "pwd"));
        db.setAdapter(adapterRegistrationEntry);
        return db;
    }
}
