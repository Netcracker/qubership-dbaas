package com.netcracker.it.dbaas.test;

import com.netcracker.it.dbaas.entity.composite.CompositeStructureDto;
import com.netcracker.it.dbaas.helpers.DbaasHelperV3;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import org.apache.hc.core5.http.HttpStatus;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

@Slf4j
public class CompositeStructureIT extends AbstractIT {

    private static final String BASE_NAMESPACE = "base-namespace";
    private static final String SATELLITE_2 = "satellite-2";
    private static final String SATELLITE_1 = "satellite-1";

    @BeforeEach
    @AfterAll
    public void cleanAllCompositeStructures() throws IOException {
        List<CompositeStructureDto> allCompositeStructures = getAllCompositeStructures();
        if (!allCompositeStructures.isEmpty()) {
            allCompositeStructures.forEach(cs -> deleteCompositeStructure(cs.getId()));
        }
        helperV3.deleteDatabases(helperV3.getClusterDbaAuthorization(), BASE_NAMESPACE);
    }

    @Test
    @Tag("Smoke")
    public void saveCompositeStructureTest() {
        getCompositeStructure(BASE_NAMESPACE, false);

        CompositeStructureDto compositeStructureDto = buildSimpleCompositeStructure();
        saveCompositeStructure(compositeStructureDto);

        CompositeStructureDto compositeStructure = getCompositeStructure(BASE_NAMESPACE, true);
        Assertions.assertEquals(BASE_NAMESPACE, compositeStructure.getId());
        Assertions.assertEquals(Set.of(SATELLITE_1, SATELLITE_2, BASE_NAMESPACE), compositeStructure.getNamespaces());

        log.info("update composite structure");
        compositeStructureDto.setNamespaces(Set.of(SATELLITE_1, SATELLITE_2, "satellite-3"));
        saveCompositeStructure(compositeStructureDto);

        List<CompositeStructureDto> allCompositeStructures = getAllCompositeStructures();
        Assertions.assertEquals(1, allCompositeStructures.size());
        CompositeStructureDto updatedCompositeStructure = getCompositeStructure(BASE_NAMESPACE, true);
        Assertions.assertEquals(BASE_NAMESPACE, updatedCompositeStructure.getId());
        Assertions.assertEquals(Set.of(SATELLITE_1, SATELLITE_2, "satellite-3", BASE_NAMESPACE), updatedCompositeStructure.getNamespaces());
        Assertions.assertEquals(updatedCompositeStructure, allCompositeStructures.getFirst());
    }

    @Test
    public void checkValidations() {
        CompositeStructureDto csWithoutId = buildSimpleCompositeStructure();
        csWithoutId.setId(null);

        log.info("check saving composite structure with compositeId=null");
        saveCompositeStructure(csWithoutId, 400);

        CompositeStructureDto csWithNullNs = buildSimpleCompositeStructure();
        csWithNullNs.setNamespaces(null);

        log.info("check saving composite structure with namespaces=null");
        saveCompositeStructure(csWithNullNs, 400);

        CompositeStructureDto compositeStructureDto = buildSimpleCompositeStructure();
        log.info("check saving composite structure {}", compositeStructureDto);
        saveCompositeStructure(compositeStructureDto);

        CompositeStructureDto anotherComposite = new CompositeStructureDto("baseline-namespace-2", Set.of(SATELLITE_1));
        log.info("check saving another composite structure {} with the same satellite ", anotherComposite);
        saveCompositeStructure(anotherComposite, 409);
    }

    @Test
    @Tag("Smoke")
    public void getCompositeStructures() {
        String baseline2 = BASE_NAMESPACE + "2";
        getCompositeStructure(BASE_NAMESPACE, false);
        getCompositeStructure(baseline2, false);
        Assertions.assertEquals(0, getAllCompositeStructures().size());

        saveCompositeStructure(new CompositeStructureDto(BASE_NAMESPACE, Set.of(SATELLITE_1, SATELLITE_2, BASE_NAMESPACE)));
        saveCompositeStructure(new CompositeStructureDto(baseline2, Set.of("ns-1", "ns-2")));

        Assertions.assertEquals(2, getAllCompositeStructures().size());

        CompositeStructureDto compositeStructure = getCompositeStructure(BASE_NAMESPACE, true);
        Assertions.assertEquals(BASE_NAMESPACE, compositeStructure.getId());
        Assertions.assertEquals(Set.of(SATELLITE_1, SATELLITE_2, BASE_NAMESPACE), compositeStructure.getNamespaces());

        CompositeStructureDto compositeStructure2 = getCompositeStructure(baseline2, true);
        Assertions.assertEquals(baseline2, compositeStructure2.getId());
        Assertions.assertEquals(Set.of("ns-1", "ns-2", baseline2), compositeStructure2.getNamespaces());
    }

    @Test
    public void deleteCompositeStructureTest() {
        deleteCompositeStructure(BASE_NAMESPACE, 404);

        saveCompositeStructure(new CompositeStructureDto(BASE_NAMESPACE, Set.of(SATELLITE_1, SATELLITE_2)));

        deleteCompositeStructure(BASE_NAMESPACE);
    }

    @Test
    public void deleteNamespaceFromComposite() throws IOException {
        saveCompositeStructure(new CompositeStructureDto(BASE_NAMESPACE, Set.of(SATELLITE_1, SATELLITE_2, BASE_NAMESPACE)));
        CompositeStructureDto compositeStructure = getCompositeStructure(BASE_NAMESPACE, true);
        Assertions.assertEquals(3, compositeStructure.getNamespaces().size());
        Assertions.assertTrue(compositeStructure.getNamespaces().contains(SATELLITE_1));

        String responseBody = helperV3.deleteDatabases(DbaasHelperV3.DATABASES_V3, helperV3.getClusterDbaAuthorization(), SATELLITE_1,
                HttpStatus.SC_OK);
        Assertions.assertEquals("Successfully deleted 0 databases and namespace specific resources in " + SATELLITE_1 + " namespace", responseBody);
        compositeStructure = getCompositeStructure(BASE_NAMESPACE, true);
        Assertions.assertEquals(2, compositeStructure.getNamespaces().size());
        Assertions.assertFalse(compositeStructure.getNamespaces().contains(SATELLITE_1));
    }

    @Test
    public void deleteDatabasesAndBaselineFromComposite() throws IOException {
        saveCompositeStructure(new CompositeStructureDto(BASE_NAMESPACE, Set.of(SATELLITE_1, SATELLITE_2, BASE_NAMESPACE)));
        CompositeStructureDto compositeStructure = getCompositeStructure(BASE_NAMESPACE, true);
        Assertions.assertEquals(3, compositeStructure.getNamespaces().size());
        Assertions.assertTrue(compositeStructure.getNamespaces().contains(BASE_NAMESPACE));

        String clusterDbaAuthorization = helperV3.getClusterDbaAuthorization();
        helperV3.createDatabase(clusterDbaAuthorization, "dbaas_auto_test_1", 201, POSTGRES_TYPE, null, BASE_NAMESPACE, false, null);

        String responseBody = helperV3.deleteDatabases(DbaasHelperV3.DATABASES_V3, helperV3.getClusterDbaAuthorization(), BASE_NAMESPACE, HttpStatus.SC_OK);
        Assertions.assertEquals("Successfully deleted 1 databases and namespace specific resources in " + BASE_NAMESPACE + " namespace", responseBody);
        getCompositeStructure(BASE_NAMESPACE, false);
    }

    private List<CompositeStructureDto> getAllCompositeStructures() {
        Request request = helperV3.createRequest("api/composite/v1/structures", helperV3.getClusterDbaAuthorization(), null, "GET");
        return Arrays.asList(helperV3.executeRequest(request, CompositeStructureDto[].class, 200));
    }

    private void deleteCompositeStructure(String compositeId) {
        deleteCompositeStructure(compositeId, 204);
    }

    private void deleteCompositeStructure(String compositeId, Integer... expectHttpCode) {
        Request request = helperV3.createRequest("api/composite/v1/structures/%s/delete".formatted(compositeId), helperV3.getClusterDbaAuthorization(), null, "DELETE");
        helperV3.executeRequest(request, null, expectHttpCode);
    }

    private void saveCompositeStructure(CompositeStructureDto compositeStructure) {
        saveCompositeStructure(compositeStructure, 204);
    }

    private void saveCompositeStructure(CompositeStructureDto compositeStructure, Integer... expectHttpCode) {
        Request request = helperV3.createRequest("api/composite/v1/structures", helperV3.getClusterDbaAuthorization(), compositeStructure, "POST");
        helperV3.executeRequest(request, null, expectHttpCode);
    }

    private CompositeStructureDto getCompositeStructure(String compositeId, boolean mustExist) {
        Request request = helperV3.createRequest("api/composite/v1/structures/%s".formatted(compositeId), helperV3.getClusterDbaAuthorization(), null, "GET");
        if (mustExist) {
            return helperV3.executeRequest(request, CompositeStructureDto.class, 200);
        }
        return helperV3.executeRequest(request, null, 404);
    }

    private CompositeStructureDto buildSimpleCompositeStructure() {
        return new CompositeStructureDto(BASE_NAMESPACE, Set.of(SATELLITE_1, SATELLITE_2, BASE_NAMESPACE));
    }
}
