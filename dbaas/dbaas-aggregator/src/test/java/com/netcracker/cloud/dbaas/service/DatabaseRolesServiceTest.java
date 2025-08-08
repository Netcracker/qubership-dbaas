package org.qubership.cloud.dbaas.service;

import org.qubership.cloud.dbaas.dto.RolesRegistrationRequest;
import org.qubership.cloud.dbaas.entity.pg.role.DatabaseRole;
import org.qubership.cloud.dbaas.dto.role.PolicyRole;
import org.qubership.cloud.dbaas.dto.role.Role;
import org.qubership.cloud.dbaas.dto.role.ServiceRole;
import org.qubership.cloud.dbaas.dto.v3.DatabaseCreateRequestV3;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseRolesDbaasRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.qubership.cloud.dbaas.service.DbaasAdapterRESTClientV2.MICROSERVICE_NAME;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DatabaseRolesServiceTest {

    public static final String POSTGRESQL_TYPE = "postgresql";
    DatabaseRolesService databaseRolesService = new DatabaseRolesService();

    private final static String TEST_MICROSERVICE_NAME = "testMicroserviceName";
    private final static String TEST_NAMESPACE = "testNamespace";
    private final static String TEST_USER_ROLE = "testUserRole";

    @Test
    void saveRequestedRoles() {
        List<PolicyRole> policyRoleList = Collections.singletonList(new PolicyRole());
        List<ServiceRole> serviceRoleList = Collections.singletonList(new ServiceRole());

        DatabaseRole expectedDatabaseRole = new DatabaseRole();
        expectedDatabaseRole.setNamespace(TEST_NAMESPACE);
        expectedDatabaseRole.setMicroserviceName(TEST_MICROSERVICE_NAME);
        expectedDatabaseRole.setPolicies(policyRoleList);
        expectedDatabaseRole.setServices(serviceRoleList);

        DatabaseRolesDbaasRepository databaseRolesDbaasRepositoryMock = mock(DatabaseRolesDbaasRepository.class);
        doNothing().when(databaseRolesDbaasRepositoryMock).save(any());
        databaseRolesService.databaseRolesDbaasRepository = databaseRolesDbaasRepositoryMock;

        RolesRegistrationRequest rolesRegistrationRequest = new RolesRegistrationRequest("qs.core.dbaas/v3");
        rolesRegistrationRequest.setServices(serviceRoleList);
        rolesRegistrationRequest.setPolicy(policyRoleList);

        databaseRolesService.saveRequestedRoles(TEST_NAMESPACE, TEST_MICROSERVICE_NAME, rolesRegistrationRequest);
        Mockito.verify(databaseRolesDbaasRepositoryMock).save(expectedDatabaseRole);
    }

    @Test
    void getSupportedRoleFromRequest_CreateRequest() {
        DatabaseRolesDbaasRepository databaseRolesDbaasRepositoryMock = mock(DatabaseRolesDbaasRepository.class);
        List<PolicyRole> policyRoleList = Collections.singletonList(createPolicyRole(POSTGRESQL_TYPE));

        List<ServiceRole> serviceRoleList = Collections.singletonList(new ServiceRole());
        DatabaseRole databaseRole = createDatabaseRole(policyRoleList, serviceRoleList);


        doReturn(List.of(databaseRole)).when(databaseRolesDbaasRepositoryMock).findAllByMicroserviceNameAndNamespace(TEST_MICROSERVICE_NAME, TEST_NAMESPACE);
        databaseRolesService.databaseRolesDbaasRepository = databaseRolesDbaasRepositoryMock;

        DatabaseCreateRequestV3 databaseCreateRequestV3 = new DatabaseCreateRequestV3();

        databaseCreateRequestV3.setOriginService(TEST_MICROSERVICE_NAME);
        HashMap<String, Object> classifier = new HashMap<>();
        classifier.put(MICROSERVICE_NAME, TEST_MICROSERVICE_NAME);
        databaseCreateRequestV3.setClassifier(classifier);
        databaseCreateRequestV3.setOriginService(TEST_MICROSERVICE_NAME);
        databaseCreateRequestV3.setType(POSTGRESQL_TYPE);

        Assertions.assertEquals(Role.ADMIN.toString(), databaseRolesService.getSupportedRoleFromRequest(databaseCreateRequestV3, databaseCreateRequestV3.getType(), TEST_NAMESPACE));
    }

    @Test
    void getSupportedRoleFromRequest_CreateRequestWithoutPolicy() {
        DatabaseRolesDbaasRepository databaseRolesDbaasRepositoryMock = mock(DatabaseRolesDbaasRepository.class);

        doReturn(null).when(databaseRolesDbaasRepositoryMock).findAllByMicroserviceNameAndNamespace(TEST_MICROSERVICE_NAME, TEST_NAMESPACE);
        databaseRolesService.databaseRolesDbaasRepository = databaseRolesDbaasRepositoryMock;

        DatabaseCreateRequestV3 databaseCreateRequestV3 = new DatabaseCreateRequestV3();

        databaseCreateRequestV3.setOriginService(TEST_MICROSERVICE_NAME);
        HashMap<String, Object> classifier = new HashMap<>();
        classifier.put(MICROSERVICE_NAME, TEST_MICROSERVICE_NAME);
        databaseCreateRequestV3.setClassifier(classifier);
        databaseCreateRequestV3.setOriginService(TEST_MICROSERVICE_NAME);
        databaseCreateRequestV3.setType(POSTGRESQL_TYPE);

        Assertions.assertEquals(Role.ADMIN.toString(), databaseRolesService.getSupportedRoleFromRequest(databaseCreateRequestV3, databaseCreateRequestV3.getType(), TEST_NAMESPACE));
    }

    @Test
    void testGetSupportedRoleFromRequest_NullExistedDatabaseRole() {
        DatabaseRolesDbaasRepository databaseRolesDbaasRepositoryMock = mock(DatabaseRolesDbaasRepository.class);

        doReturn(null).when(databaseRolesDbaasRepositoryMock).findAllByMicroserviceNameAndNamespace(TEST_MICROSERVICE_NAME, TEST_NAMESPACE);
        databaseRolesService.databaseRolesDbaasRepository = databaseRolesDbaasRepositoryMock;

        DatabaseCreateRequestV3 databaseCreateRequestV3 = new DatabaseCreateRequestV3();

        databaseCreateRequestV3.setOriginService(TEST_MICROSERVICE_NAME);
        HashMap<String, Object> classifier = new HashMap<>();
        classifier.put(MICROSERVICE_NAME, TEST_MICROSERVICE_NAME);
        databaseCreateRequestV3.setClassifier(classifier);
        databaseCreateRequestV3.setOriginService(TEST_MICROSERVICE_NAME);
        databaseCreateRequestV3.setType(POSTGRESQL_TYPE);
        databaseCreateRequestV3.setUserRole("admin");

        Assertions.assertEquals(Role.ADMIN.toString(), databaseRolesService.getSupportedRoleFromRequest(databaseCreateRequestV3, databaseCreateRequestV3.getType(), TEST_NAMESPACE));
    }

    @Test
    void testGetSupportedRoleFromRequest_CheckGlobalPermissions() {
        DatabaseRolesDbaasRepository databaseRolesDbaasRepositoryMock = mock(DatabaseRolesDbaasRepository.class);

        doReturn(null).when(databaseRolesDbaasRepositoryMock).findAllByMicroserviceNameAndNamespace("another-service", TEST_NAMESPACE);
        databaseRolesService.databaseRolesDbaasRepository = databaseRolesDbaasRepositoryMock;

        DatabaseCreateRequestV3 databaseCreateRequestV3 = new DatabaseCreateRequestV3();

        databaseCreateRequestV3.setOriginService(TEST_MICROSERVICE_NAME);
        HashMap<String, Object> classifier = new HashMap<>();
        classifier.put(MICROSERVICE_NAME, "another-service");
        databaseCreateRequestV3.setClassifier(classifier);
        databaseCreateRequestV3.setOriginService("cdc-streaming-platform");
        databaseCreateRequestV3.setType(POSTGRESQL_TYPE);
        databaseCreateRequestV3.setUserRole("streaming");

        Assertions.assertEquals("streaming", databaseRolesService.getSupportedRoleFromRequest(databaseCreateRequestV3, databaseCreateRequestV3.getType(), TEST_NAMESPACE));
    }

    @Test
    void testGetSupportedRoleFromRequest_CheckGlobalPermissionsWhenDisabled() {
        DatabaseRolesDbaasRepository databaseRolesDbaasRepositoryMock = mock(DatabaseRolesDbaasRepository.class);
        List<PolicyRole> policyRoleList = Collections.singletonList(createPolicyRole(POSTGRESQL_TYPE));

        ServiceRole serviceRole = new ServiceRole();
        serviceRole.setName("some-name");
        serviceRole.setRoles(List.of("rw"));
        List<ServiceRole> serviceRoleList = Collections.singletonList(serviceRole);
        DatabaseRole databaseRole = createDatabaseRole(policyRoleList, serviceRoleList);
        databaseRole.setDisableGlobalPermissions(true);

        doReturn(List.of(databaseRole)).when(databaseRolesDbaasRepositoryMock).findAllByMicroserviceNameAndNamespace("another-service", TEST_NAMESPACE);
        databaseRolesService.databaseRolesDbaasRepository = databaseRolesDbaasRepositoryMock;

        DatabaseCreateRequestV3 databaseCreateRequestV3 = new DatabaseCreateRequestV3();

        databaseCreateRequestV3.setOriginService(TEST_MICROSERVICE_NAME);
        HashMap<String, Object> classifier = new HashMap<>();
        classifier.put(MICROSERVICE_NAME, "another-service");
        databaseCreateRequestV3.setClassifier(classifier);
        databaseCreateRequestV3.setOriginService("cdc-streaming-platform");
        databaseCreateRequestV3.setType(POSTGRESQL_TYPE);
        databaseCreateRequestV3.setUserRole("streaming");

        Assertions.assertNull(databaseRolesService.getSupportedRoleFromRequest(databaseCreateRequestV3, databaseCreateRequestV3.getType(), TEST_NAMESPACE));
    }

    @Test
    void testGetSupportedRoleFromRequest_RequestFromTrustServiceWithoutRole() {
        ServiceRole serviceRole = createServiceRole("trusted-ns", List.of("admin"));
        DatabaseRole expectedDatabaseRole = createDatabaseRole(null, List.of(serviceRole));

        DatabaseRolesDbaasRepository databaseRolesDbaasRepositoryMock = mock(DatabaseRolesDbaasRepository.class);
        when(databaseRolesDbaasRepositoryMock.findAllByMicroserviceNameAndNamespace(TEST_MICROSERVICE_NAME, TEST_NAMESPACE))
                .thenReturn(Collections.singletonList(expectedDatabaseRole));
        databaseRolesService.databaseRolesDbaasRepository = databaseRolesDbaasRepositoryMock;

        DatabaseCreateRequestV3 databaseCreateRequestV3 = new DatabaseCreateRequestV3();

        HashMap<String, Object> classifier = new HashMap<>();
        classifier.put(MICROSERVICE_NAME, TEST_MICROSERVICE_NAME);
        databaseCreateRequestV3.setClassifier(classifier);
        databaseCreateRequestV3.setOriginService("trusted-ns");
        databaseCreateRequestV3.setType(POSTGRESQL_TYPE);

        Assertions.assertEquals(Role.ADMIN.toString(), databaseRolesService.getSupportedRoleFromRequest(databaseCreateRequestV3, databaseCreateRequestV3.getType(), TEST_NAMESPACE));
    }

    @Test
    void getSupportedRoleFromRequest_ReturnRoleToOwnerWithoutPolicy() {
        DatabaseRolesDbaasRepository databaseRolesDbaasRepositoryMock = mock(DatabaseRolesDbaasRepository.class);
        List<ServiceRole> serviceRoleList = Collections.singletonList(new ServiceRole());
        DatabaseRole databaseRole = createDatabaseRole(null, serviceRoleList);


        doReturn(List.of(databaseRole)).when(databaseRolesDbaasRepositoryMock)
                .findAllByMicroserviceNameAndNamespace(TEST_MICROSERVICE_NAME, TEST_NAMESPACE);
        databaseRolesService.databaseRolesDbaasRepository = databaseRolesDbaasRepositoryMock;

        DatabaseCreateRequestV3 databaseCreateRequestV3 = new DatabaseCreateRequestV3();

        databaseCreateRequestV3.setOriginService(TEST_MICROSERVICE_NAME);
        HashMap<String, Object> classifier = new HashMap<>();
        classifier.put(MICROSERVICE_NAME, TEST_MICROSERVICE_NAME);
        databaseCreateRequestV3.setClassifier(classifier);
        databaseCreateRequestV3.setOriginService(TEST_MICROSERVICE_NAME);
        databaseCreateRequestV3.setType(POSTGRESQL_TYPE);

        Assertions.assertEquals(Role.ADMIN.toString(), databaseRolesService.getSupportedRoleFromRequest(databaseCreateRequestV3, databaseCreateRequestV3.getType(), TEST_NAMESPACE));

        databaseCreateRequestV3.setUserRole(TEST_USER_ROLE);
        Assertions.assertEquals(TEST_USER_ROLE.toLowerCase(), databaseRolesService.getSupportedRoleFromRequest(databaseCreateRequestV3, databaseCreateRequestV3.getType(), TEST_NAMESPACE));
    }

    @Test
    void getSupportedRoleFromRequest_ReturnRoleToOwnerWithEmptyPolicy() {
        DatabaseRolesDbaasRepository databaseRolesDbaasRepositoryMock = mock(DatabaseRolesDbaasRepository.class);
        List<ServiceRole> serviceRoleList = Collections.singletonList(new ServiceRole());
        DatabaseRole databaseRole = createDatabaseRole(new ArrayList<>(), serviceRoleList);


        doReturn(List.of(databaseRole)).when(databaseRolesDbaasRepositoryMock)
                .findAllByMicroserviceNameAndNamespace(TEST_MICROSERVICE_NAME, TEST_NAMESPACE);
        databaseRolesService.databaseRolesDbaasRepository = databaseRolesDbaasRepositoryMock;

        DatabaseCreateRequestV3 databaseCreateRequestV3 = new DatabaseCreateRequestV3();

        databaseCreateRequestV3.setOriginService(TEST_MICROSERVICE_NAME);
        HashMap<String, Object> classifier = new HashMap<>();
        classifier.put(MICROSERVICE_NAME, TEST_MICROSERVICE_NAME);
        databaseCreateRequestV3.setClassifier(classifier);
        databaseCreateRequestV3.setOriginService(TEST_MICROSERVICE_NAME);
        databaseCreateRequestV3.setType(POSTGRESQL_TYPE);

        Assertions.assertEquals(Role.ADMIN.toString(), databaseRolesService.getSupportedRoleFromRequest(databaseCreateRequestV3, databaseCreateRequestV3.getType(), TEST_NAMESPACE));
    }

    @Test
    void getSupportedRoleFromRequest_ReturnRoleToOwnerWithPolicyNullAdditionalRule() {
        DatabaseRolesDbaasRepository databaseRolesDbaasRepositoryMock = mock(DatabaseRolesDbaasRepository.class);
        List<ServiceRole> serviceRoleList = Collections.singletonList(new ServiceRole());
        PolicyRole policyRole = new PolicyRole();
        policyRole.setType(POSTGRESQL_TYPE);
        List<PolicyRole> policyRoleList = Collections.singletonList(policyRole);
        DatabaseRole databaseRole = createDatabaseRole(policyRoleList, serviceRoleList);


        doReturn(List.of(databaseRole)).when(databaseRolesDbaasRepositoryMock)
                .findAllByMicroserviceNameAndNamespace(TEST_MICROSERVICE_NAME, TEST_NAMESPACE);
        databaseRolesService.databaseRolesDbaasRepository = databaseRolesDbaasRepositoryMock;

        DatabaseCreateRequestV3 databaseCreateRequestV3 = new DatabaseCreateRequestV3();

        databaseCreateRequestV3.setOriginService(TEST_MICROSERVICE_NAME);
        HashMap<String, Object> classifier = new HashMap<>();
        classifier.put(MICROSERVICE_NAME, TEST_MICROSERVICE_NAME);
        databaseCreateRequestV3.setClassifier(classifier);
        databaseCreateRequestV3.setOriginService(TEST_MICROSERVICE_NAME);
        databaseCreateRequestV3.setType(POSTGRESQL_TYPE);
        databaseCreateRequestV3.setUserRole("any_Role");

        Assertions.assertEquals("any_role", databaseRolesService.getSupportedRoleFromRequest(databaseCreateRequestV3, databaseCreateRequestV3.getType(), TEST_NAMESPACE));
    }

    @Test
    void getSupportedRoleFromRequest_ReturnRoleToOwnerWithPolicyEmptyAdditionalRule() {
        DatabaseRolesDbaasRepository databaseRolesDbaasRepositoryMock = mock(DatabaseRolesDbaasRepository.class);
        List<ServiceRole> serviceRoleList = Collections.singletonList(new ServiceRole());
        PolicyRole policyRole = new PolicyRole();
        policyRole.setAdditionalRole(new ArrayList<>());
        policyRole.setType(POSTGRESQL_TYPE);
        List<PolicyRole> policyRoleList = Collections.singletonList(policyRole);
        DatabaseRole databaseRole = createDatabaseRole(policyRoleList, serviceRoleList);


        doReturn(List.of(databaseRole)).when(databaseRolesDbaasRepositoryMock)
                .findAllByMicroserviceNameAndNamespace(TEST_MICROSERVICE_NAME, TEST_NAMESPACE);
        databaseRolesService.databaseRolesDbaasRepository = databaseRolesDbaasRepositoryMock;

        DatabaseCreateRequestV3 databaseCreateRequestV3 = new DatabaseCreateRequestV3();

        databaseCreateRequestV3.setOriginService(TEST_MICROSERVICE_NAME);
        HashMap<String, Object> classifier = new HashMap<>();
        classifier.put(MICROSERVICE_NAME, TEST_MICROSERVICE_NAME);
        databaseCreateRequestV3.setClassifier(classifier);
        databaseCreateRequestV3.setOriginService(TEST_MICROSERVICE_NAME);
        databaseCreateRequestV3.setType(POSTGRESQL_TYPE);
        databaseCreateRequestV3.setUserRole("any_Role");

        Assertions.assertEquals("any_role", databaseRolesService.getSupportedRoleFromRequest(databaseCreateRequestV3, databaseCreateRequestV3.getType(), TEST_NAMESPACE));
    }

    @Test
    void getSupportedRoleFromRequest_ReturnRoleToOwnerWithPolicy() {
        DatabaseRolesDbaasRepository databaseRolesDbaasRepositoryMock = mock(DatabaseRolesDbaasRepository.class);
        List<ServiceRole> serviceRoleList = Collections.singletonList(new ServiceRole());
        PolicyRole policyRole = new PolicyRole();
        policyRole.setAdditionalRole(Collections.singletonList("rw"));
        policyRole.setType(POSTGRESQL_TYPE);
        List<PolicyRole> policyRoleList = Collections.singletonList(policyRole);
        DatabaseRole databaseRole = createDatabaseRole(policyRoleList, serviceRoleList);


        doReturn(List.of(databaseRole)).when(databaseRolesDbaasRepositoryMock)
                .findAllByMicroserviceNameAndNamespace(TEST_MICROSERVICE_NAME, TEST_NAMESPACE);
        databaseRolesService.databaseRolesDbaasRepository = databaseRolesDbaasRepositoryMock;

        DatabaseCreateRequestV3 databaseCreateRequestV3 = new DatabaseCreateRequestV3();

        databaseCreateRequestV3.setOriginService(TEST_MICROSERVICE_NAME);
        HashMap<String, Object> classifier = new HashMap<>();
        classifier.put(MICROSERVICE_NAME, TEST_MICROSERVICE_NAME);
        databaseCreateRequestV3.setClassifier(classifier);
        databaseCreateRequestV3.setOriginService(TEST_MICROSERVICE_NAME);
        databaseCreateRequestV3.setType(POSTGRESQL_TYPE);
        databaseCreateRequestV3.setUserRole("rw");

        Assertions.assertEquals("rw", databaseRolesService.getSupportedRoleFromRequest(databaseCreateRequestV3, databaseCreateRequestV3.getType(), TEST_NAMESPACE));
    }

    @Test
    void getSupportedRoleFromRequest_ReturnRoleToOwnerWithEmptyPolicyForSpecifiedDatabaseType() {
        String databaseTypeWithoutPolicy = "mongo";
        DatabaseRolesDbaasRepository databaseRolesDbaasRepositoryMock = mock(DatabaseRolesDbaasRepository.class);
        List<PolicyRole> policyRoleList = Collections.singletonList(createPolicyRole(POSTGRESQL_TYPE));

        List<ServiceRole> serviceRoleList = Collections.singletonList(new ServiceRole());
        DatabaseRole databaseRole = createDatabaseRole(policyRoleList, serviceRoleList);


        doReturn(List.of(databaseRole)).when(databaseRolesDbaasRepositoryMock).findAllByMicroserviceNameAndNamespace(TEST_MICROSERVICE_NAME, TEST_NAMESPACE);
        databaseRolesService.databaseRolesDbaasRepository = databaseRolesDbaasRepositoryMock;

        DatabaseCreateRequestV3 databaseCreateRequestV3 = new DatabaseCreateRequestV3();

        databaseCreateRequestV3.setOriginService(TEST_MICROSERVICE_NAME);
        HashMap<String, Object> classifier = new HashMap<>();
        classifier.put(MICROSERVICE_NAME, TEST_MICROSERVICE_NAME);
        databaseCreateRequestV3.setClassifier(classifier);
        databaseCreateRequestV3.setOriginService(TEST_MICROSERVICE_NAME);
        databaseCreateRequestV3.setType(databaseTypeWithoutPolicy);

        Assertions.assertEquals(Role.ADMIN.toString(), databaseRolesService.getSupportedRoleFromRequest(databaseCreateRequestV3, databaseCreateRequestV3.getType(), TEST_NAMESPACE));
    }

    @Test
    void GetAccessGrantsTest() {
        DatabaseRolesDbaasRepository databaseRolesDbaasRepositoryMock = mock(DatabaseRolesDbaasRepository.class);
        List<ServiceRole> serviceRoleList = Collections.singletonList(new ServiceRole());
        PolicyRole policyRole = new PolicyRole();
        policyRole.setAdditionalRole(Collections.singletonList("rw"));
        policyRole.setType(POSTGRESQL_TYPE);
        List<PolicyRole> policyRoleList = Collections.singletonList(policyRole);
        DatabaseRole databaseRole = createDatabaseRole(policyRoleList, serviceRoleList);
        databaseRolesService.databaseRolesDbaasRepository = databaseRolesDbaasRepositoryMock;
        doReturn(List.of(databaseRole)).when(databaseRolesDbaasRepositoryMock)
                .findAllByMicroserviceNameAndNamespace(TEST_MICROSERVICE_NAME, TEST_NAMESPACE);
        Assertions.assertEquals(databaseRolesService.getAccessGrants(TEST_NAMESPACE, TEST_MICROSERVICE_NAME), Optional.of(databaseRole));
    }

    @Test
    void GetAccessGrantsWithEmptyDeclarativePolicyTest() {
        DatabaseRolesDbaasRepository databaseRolesDbaasRepositoryMock = mock(DatabaseRolesDbaasRepository.class);
        databaseRolesService.databaseRolesDbaasRepository = databaseRolesDbaasRepositoryMock;
        doReturn(null).when(databaseRolesDbaasRepositoryMock)
                .findAllByMicroserviceNameAndNamespace(TEST_MICROSERVICE_NAME, TEST_NAMESPACE);
        Assertions.assertEquals(databaseRolesService.getAccessGrants(TEST_NAMESPACE, TEST_MICROSERVICE_NAME), Optional.empty());
    }


    private DatabaseRole createDatabaseRole(List<PolicyRole> policyRoleList, List<ServiceRole> serviceRoleList) {
        DatabaseRole databaseRole = new DatabaseRole();
        databaseRole.setNamespace(TEST_NAMESPACE);
        databaseRole.setMicroserviceName(TEST_MICROSERVICE_NAME);
        databaseRole.setPolicies(policyRoleList);
        databaseRole.setServices(serviceRoleList);
        databaseRole.setTimeRoleCreation(new Date());
        databaseRole.setDisableGlobalPermissions(false);
        return databaseRole;
    }

    private PolicyRole createPolicyRole(String type){
        PolicyRole policyRole = new PolicyRole();
        policyRole.setType(type);
        policyRole.setDefaultRole(Role.ADMIN.toString());
        return policyRole;
    }

    private ServiceRole createServiceRole(String name, List<String> roles) {
        ServiceRole serviceRole = new ServiceRole();
        serviceRole.setName(name);
        serviceRole.setRoles(roles);
        return serviceRole;
    }
}
