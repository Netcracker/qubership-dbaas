package com.netcracker.it.dbaas.test;

import com.netcracker.it.dbaas.entity.composite.CompositeStructureDto;
import com.netcracker.it.dbaas.helpers.CompositeStructureHelperV1;
import com.netcracker.it.dbaas.helpers.DbaasHelperV3;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.core5.http.HttpStatus;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.util.List;
import java.util.Set;

@Slf4j
public class CompositeStructureIT extends AbstractIT {

    private static final String BASE_NAMESPACE = "base-namespace";
    private static final String SATELLITE_2 = "satellite-2";
    private static final String SATELLITE_1 = "satellite-1";

    private static CompositeStructureHelperV1 compositeHelperV1;

    @BeforeAll
    static void setUpCompositeHelper() {
        compositeHelperV1 = new CompositeStructureHelperV1(helperV3);
    }

    @BeforeEach
    @AfterAll
    public void cleanAllCompositeStructures() throws IOException {
        List<CompositeStructureDto> allCompositeStructures = compositeHelperV1.getCompositeStructures();
        if (!allCompositeStructures.isEmpty()) {
            allCompositeStructures.forEach(cs -> compositeHelperV1.deleteCompositeStructure(cs.getId()));
        }
        helperV3.deleteDatabases(helperV3.getClusterDbaAuthorization(), BASE_NAMESPACE);
    }

    @Test
    @Tag("Smoke")
    public void saveCompositeStructureTest() {
        compositeHelperV1.getCompositeStructure(BASE_NAMESPACE, false);

        CompositeStructureDto compositeStructureDto = buildSimpleCompositeStructure();
        compositeHelperV1.saveCompositeStructure(compositeStructureDto);

        CompositeStructureDto compositeStructure = compositeHelperV1.getCompositeStructure(BASE_NAMESPACE, true);
        Assertions.assertEquals(BASE_NAMESPACE, compositeStructure.getId());
        Assertions.assertEquals(Set.of(SATELLITE_1, SATELLITE_2, BASE_NAMESPACE), compositeStructure.getNamespaces());

        log.info("update composite structure");
        compositeStructureDto.setNamespaces(Set.of(SATELLITE_1, SATELLITE_2, "satellite-3"));
        compositeHelperV1.saveCompositeStructure(compositeStructureDto);

        List<CompositeStructureDto> allCompositeStructures = compositeHelperV1.getCompositeStructures();
        Assertions.assertEquals(1, allCompositeStructures.size());
        CompositeStructureDto updatedCompositeStructure = compositeHelperV1.getCompositeStructure(BASE_NAMESPACE, true);
        Assertions.assertEquals(BASE_NAMESPACE, updatedCompositeStructure.getId());
        Assertions.assertEquals(Set.of(SATELLITE_1, SATELLITE_2, "satellite-3", BASE_NAMESPACE), updatedCompositeStructure.getNamespaces());
        Assertions.assertEquals(updatedCompositeStructure, allCompositeStructures.getFirst());
    }

    @Test
    public void checkValidations() {
        CompositeStructureDto csWithoutId = buildSimpleCompositeStructure();
        csWithoutId.setId(null);

        log.info("check saving composite structure with compositeId=null");
        compositeHelperV1.saveCompositeStructure(csWithoutId, 400);

        CompositeStructureDto csWithNullNs = buildSimpleCompositeStructure();
        csWithNullNs.setNamespaces(null);

        log.info("check saving composite structure with namespaces=null");
        compositeHelperV1.saveCompositeStructure(csWithNullNs, 400);

        CompositeStructureDto compositeStructureDto = buildSimpleCompositeStructure();
        log.info("check saving composite structure {}", compositeStructureDto);
        compositeHelperV1.saveCompositeStructure(compositeStructureDto);

        CompositeStructureDto anotherComposite = new CompositeStructureDto("baseline-namespace-2", Set.of(SATELLITE_1));
        log.info("check saving another composite structure {} with the same satellite ", anotherComposite);
        compositeHelperV1.saveCompositeStructure(anotherComposite, 409);
    }

    @Test
    @Tag("Smoke")
    public void getCompositeStructures() {
        String baseline2 = BASE_NAMESPACE + "2";
        compositeHelperV1.getCompositeStructure(BASE_NAMESPACE, false);
        compositeHelperV1.getCompositeStructure(baseline2, false);
        Assertions.assertEquals(0, compositeHelperV1.getCompositeStructures().size());

        compositeHelperV1.saveCompositeStructure(new CompositeStructureDto(BASE_NAMESPACE, Set.of(SATELLITE_1, SATELLITE_2, BASE_NAMESPACE)));
        compositeHelperV1.saveCompositeStructure(new CompositeStructureDto(baseline2, Set.of("ns-1", "ns-2")));

        Assertions.assertEquals(2, compositeHelperV1.getCompositeStructures().size());

        CompositeStructureDto compositeStructure = compositeHelperV1.getCompositeStructure(BASE_NAMESPACE, true);
        Assertions.assertEquals(BASE_NAMESPACE, compositeStructure.getId());
        Assertions.assertEquals(Set.of(SATELLITE_1, SATELLITE_2, BASE_NAMESPACE), compositeStructure.getNamespaces());

        CompositeStructureDto compositeStructure2 = compositeHelperV1.getCompositeStructure(baseline2, true);
        Assertions.assertEquals(baseline2, compositeStructure2.getId());
        Assertions.assertEquals(Set.of("ns-1", "ns-2", baseline2), compositeStructure2.getNamespaces());
    }

    @Test
    public void deleteCompositeStructureTest() {
        compositeHelperV1.deleteCompositeStructure(BASE_NAMESPACE, 404);

        compositeHelperV1.saveCompositeStructure(new CompositeStructureDto(BASE_NAMESPACE, Set.of(SATELLITE_1, SATELLITE_2)));

        compositeHelperV1.deleteCompositeStructure(BASE_NAMESPACE);
    }

    @Test
    public void deleteNamespaceFromComposite() throws IOException {
        compositeHelperV1.saveCompositeStructure(new CompositeStructureDto(BASE_NAMESPACE, Set.of(SATELLITE_1, SATELLITE_2, BASE_NAMESPACE)));
        CompositeStructureDto compositeStructure = compositeHelperV1.getCompositeStructure(BASE_NAMESPACE, true);
        Assertions.assertEquals(3, compositeStructure.getNamespaces().size());
        Assertions.assertTrue(compositeStructure.getNamespaces().contains(SATELLITE_1));

        String responseBody = helperV3.deleteDatabases(DbaasHelperV3.DATABASES_V3, helperV3.getClusterDbaAuthorization(), SATELLITE_1,
                HttpStatus.SC_OK);
        Assertions.assertEquals("Successful '" + SATELLITE_1 + "' namespace cleanup: namespace specific resources are deleted synchronously - success, " +
                "0 databases are deleted synchronously - success, 0 databases are marked for drop and asynchronous deletion is scheduled - success", responseBody);
        compositeStructure = compositeHelperV1.getCompositeStructure(BASE_NAMESPACE, true);
        Assertions.assertEquals(2, compositeStructure.getNamespaces().size());
        Assertions.assertFalse(compositeStructure.getNamespaces().contains(SATELLITE_1));
    }

    @Tag("postgresql")
    @Test
    public void deleteDatabasesAndBaselineFromComposite() throws IOException {
        compositeHelperV1.saveCompositeStructure(new CompositeStructureDto(BASE_NAMESPACE, Set.of(SATELLITE_1, SATELLITE_2, BASE_NAMESPACE)));
        CompositeStructureDto compositeStructure = compositeHelperV1.getCompositeStructure(BASE_NAMESPACE, true);
        Assertions.assertEquals(3, compositeStructure.getNamespaces().size());
        Assertions.assertTrue(compositeStructure.getNamespaces().contains(BASE_NAMESPACE));

        String clusterDbaAuthorization = helperV3.getClusterDbaAuthorization();
        helperV3.createDatabase(clusterDbaAuthorization, "dbaas_auto_test_1", 201, POSTGRES_TYPE, null, BASE_NAMESPACE, false, null);

        String responseBody = helperV3.deleteDatabases(DbaasHelperV3.DATABASES_V3, helperV3.getClusterDbaAuthorization(), BASE_NAMESPACE, HttpStatus.SC_OK);
        Assertions.assertEquals("Successful '" + BASE_NAMESPACE + "' namespace cleanup: namespace specific resources are deleted synchronously - success, " +
                "0 databases are deleted synchronously - success, 1 databases are marked for drop and asynchronous deletion is scheduled - success", responseBody);
        compositeHelperV1.getCompositeStructure(BASE_NAMESPACE, false);
    }

    private CompositeStructureDto buildSimpleCompositeStructure() {
        return new CompositeStructureDto(BASE_NAMESPACE, Set.of(SATELLITE_1, SATELLITE_2, BASE_NAMESPACE));
    }
}
