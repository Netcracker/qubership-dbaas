package org.qubership.cloud.dbaas.controller.abstact;

import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.exceptions.ForbiddenDeleteOperationException;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import org.qubership.cloud.dbaas.service.DBaaService;
import org.qubership.cloud.dbaas.service.DbaaSHelper;
import org.qubership.cloud.dbaas.service.ResponseHelper;
import org.qubership.cloud.dbaas.service.composite.CompositeNamespaceService;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public abstract class AbstractDatabaseAdministrationController {
    @Inject
    protected DbaaSHelper dbaaSHelper;
    @Inject
    protected DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;
    @Inject
    protected DBaaService dBaaService;
    @Inject
    CompositeNamespaceService compositeNamespaceService;
    @Inject
    protected ResponseHelper responseHelper;

    protected void checkDeletionRequest() {
        if (dbaaSHelper.isProductionMode()) {
            throw new ForbiddenDeleteOperationException();
        }
    }

    protected Response dropAllDatabasesInNamespace(String namespace, Boolean deleteRules) {
        boolean namespaceInComposite = compositeNamespaceService.isNamespaceInComposite(namespace);
        List<DatabaseRegistry> namespaceDatabases = databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(namespace);

        if (!namespaceInComposite && dBaaService.checkNamespaceAlreadyDropped(namespace, namespaceDatabases)) {
            log.info("Namespace {} is empty, dropping is not needed", namespace);
            return Response.ok(String.format("namespace %s doesn't contain any databases and namespace specific resources", namespace)).build();
        }
        checkDeletionRequest();
        compositeNamespaceService.deleteNamespace(namespace);
        boolean removeRules = deleteRules == null ? true : deleteRules;
        Long number = dBaaService.deleteDatabasesAsync(namespace, namespaceDatabases, removeRules);
        return Response.ok(String.format("Successfully deleted %d databases and namespace specific resources in %s namespace", number, namespace)).build();
    }
}
