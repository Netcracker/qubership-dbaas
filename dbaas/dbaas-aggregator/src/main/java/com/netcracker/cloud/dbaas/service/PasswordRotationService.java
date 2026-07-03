package com.netcracker.cloud.dbaas.service;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

import com.netcracker.cloud.dbaas.dto.EnsuredUser;
import com.netcracker.cloud.dbaas.dto.PasswordChangeResponse;
import com.netcracker.cloud.dbaas.dto.Source;
import com.netcracker.cloud.dbaas.dto.v3.PasswordChangeRequestV3;
import com.netcracker.cloud.dbaas.entity.pg.Database;
import com.netcracker.cloud.dbaas.entity.pg.DatabaseRegistry;
import com.netcracker.cloud.dbaas.entity.pg.DbResource;
import com.netcracker.cloud.dbaas.exceptions.PasswordChangeFailedException;
import com.netcracker.cloud.dbaas.exceptions.PasswordChangeValidationException;
import com.netcracker.cloud.dbaas.exceptions.UnknownErrorCodeException;
import com.netcracker.cloud.dbaas.exceptions.UnregisteredPhysicalDatabaseException;
import com.netcracker.cloud.dbaas.repositories.dbaas.LogicalDbDbaasRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.netcracker.cloud.dbaas.Constants.ROLE;

/**
 * Rotation is intentionally not wrapped in a single outer transaction. Each adapter call
 * ({@code adapter.ensureUser}) is an irreversible side effect on the real database, so every successful
 * rotation must be committed independently rather than rolled back when a later database in the same
 * batch fails. The per-database commit boundary lives in {@link PasswordRotationCommitService}.
 * <p>
 * The public entry points are annotated {@link Transactional.TxType#NOT_SUPPORTED} to suspend any
 * ambient transaction a future caller might open. Without this, the committer's {@code REQUIRED} boundary
 * would join that outer transaction and a late sibling failure could again roll back databases that were
 * already rotated on the adapter. Suspending guarantees each {@code commitRotation}
 * opens its own fresh transaction regardless of the call chain.
 */
@Slf4j
@ApplicationScoped
public class PasswordRotationService {

    @Inject
    PasswordEncryption encryption;
    @Inject
    LogicalDbDbaasRepository logicalDbDbaasRepository;
    @Inject
    DBaaService dBaaService;
    @Inject
    PasswordRotationCommitService passwordRotationCommitService;

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public PasswordChangeResponse changeUserPassword(PasswordChangeRequestV3 passwordChangeRequest, String namespace) {
        return changeUserPassword(passwordChangeRequest, namespace, null);
    }

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public PasswordChangeResponse changeUserPassword(PasswordChangeRequestV3 passwordChangeRequest, String namespace, String role) {
        String dbType = passwordChangeRequest.getType();
        List<DatabaseRegistry> databasesForChangePassword = new ArrayList<>();
        if (!MapUtils.isEmpty(passwordChangeRequest.getClassifier())) {
            passwordChangeRequest.getClassifier().put("namespace", namespace);
            if (!dBaaService.isValidClassifierV3(passwordChangeRequest.getClassifier())) {
                throw new PasswordChangeValidationException("PasswordChangeRequest =" + passwordChangeRequest + " contains not valid V3 classifier",
                        Source.builder().pointer("/classifier").build());
            }
            Optional<DatabaseRegistry> databaseRegistry;
StructuredLog.info(log, "Password will be changed from one database with classifier and type", "classifier", passwordChangeRequest.getClassifier(), "type", passwordChangeRequest.getType());
            databaseRegistry = logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().getDatabaseByClassifierAndType(passwordChangeRequest.getClassifier(), dbType);
            if (databaseRegistry.isPresent()) {
                databasesForChangePassword.add(databaseRegistry.get());
            }
        } else {
            databasesForChangePassword = logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().findInternalDatabaseRegistryByNamespace(namespace)
                    .stream()
                    .filter(databaseRegistry -> databaseRegistry.getType().equals(dbType))
                    .filter(Predicate.not(DeletionService::isMarkedForDrop))
                    .collect(Collectors.toList());
StructuredLog.info(log, "The password will be change from databases which are located in namespace and have database type", "count", databasesForChangePassword.size(), "namespace", namespace, "dbType", dbType);
StructuredLog.debug(log, "List of databases", "databasesForChangePassword", databasesForChangePassword);
        }
        Map<DbaasAdapter, Boolean> adaptersAndUserSupportedMap;
        try {
            adaptersAndUserSupportedMap = getAdaptersAndUserSupportedMap(databasesForChangePassword);
StructuredLog.debug(log, "Map of adapters and user support opportunities", "adaptersAndUserSupportedMap", adaptersAndUserSupportedMap);
        } catch (Exception e) {
            throw new UnknownErrorCodeException(e);
        }
        List<String> unsupportedUserAdapters = adaptersAndUserSupportedMap.entrySet().stream()
                .filter(dbaasAdapterBooleanEntry -> !dbaasAdapterBooleanEntry.getValue())
                .map(dbaasAdapterBooleanEntry -> dbaasAdapterBooleanEntry.getKey().adapterAddress())
                .collect(Collectors.toList());
        if (!CollectionUtils.isEmpty(unsupportedUserAdapters)) {
            throw new PasswordChangeValidationException("The following adapters: " + unsupportedUserAdapters + " do not support user password change",
                    Source.builder().build());
        }
        PasswordChangeResponse response;
        response = performChangePassword(databasesForChangePassword, role);
        if (!CollectionUtils.isEmpty(response.getFailed())) {
            throw new PasswordChangeFailedException(response, response.getFailedHttpStatus());
        } else {
            return response;
        }
    }

    PasswordChangeResponse performChangePassword(List<DatabaseRegistry> databasesForChangePassword, @Nullable String role) {
        StructuredLog.info(log, "Change password", "classifier", role == null ? "for whole database roles" : "for role=" + role);
        PasswordChangeResponse response = new PasswordChangeResponse();
        long count = databasesForChangePassword.stream().map(databaseRegistry -> {
            Database database = databaseRegistry.getDatabase();
            long sum = 0L;
            DbaasAdapter adapter = dBaaService.getAdapter(database.getAdapterId()).get();
            List<Map<String, Object>> connectionProperties = database.getConnectionProperties();
            if (role != null) {
                connectionProperties = connectionProperties.stream()
                        .filter(cp -> cp.get(ROLE) instanceof String && role.equalsIgnoreCase((String) cp.get(ROLE)))
                        .collect(Collectors.toList());
            }
            for (Map<String, Object> cp : connectionProperties) {
                try {
                    String dbName = database.getName();
                    String password = null;
                    EnsuredUser ensuredUser;
                    ensuredUser = dBaaService.recreateUsers(adapter, (String) cp.get("username"), dbName, password, (String) cp.get(ROLE));

StructuredLog.info(log, "Get resources", "arg0", ensuredUser.getConnectionProperties());
                    encryption.deletePassword(database, (String) cp.get(ROLE));
                    List<Map<String, Object>> replaceConnectionProperties = ConnectionPropertiesUtils.replaceConnectionProperties((String) cp.get(ROLE), database.getConnectionProperties(), ensuredUser.getConnectionProperties());
                    database.setConnectionProperties(replaceConnectionProperties);

                    database.setResources(getMergedResources(database.getResources(), ensuredUser.getResources()));


                    response.putSuccessEntity(databaseRegistry.getClassifier(), new HashMap<>(ConnectionPropertiesUtils.getConnectionProperties(database.getConnectionProperties(), (String) cp.get(ROLE))));
                    encryption.encryptPassword(database, (String) cp.get(ROLE));

StructuredLog.info(log, "The password was changed successfully from database with classifier and type and role", "classifier", databaseRegistry.getClassifier(), "database", databaseRegistry.getType(), "arg2", (String) cp.get(ROLE));
                    sum += 1L;
                } catch (WebApplicationException e) {
                    response.putFailedEntity(databaseRegistry.getClassifier(), e.getMessage());
StructuredLog.error(log, "Faled during change password from database with classifier and type and role. Error:", e, "classifier", databaseRegistry.getClassifier(), "database", databaseRegistry.getType(), "arg2", (String) cp.get(ROLE));
                    if (e.getResponse().getStatus() > response.getFailedHttpStatus()) {
                        response.setFailedHttpStatus(e.getResponse().getStatus());
                    }
                }
            }
            if (sum > 0) {
                passwordRotationCommitService.commitRotation(databaseRegistry);
            }
            return sum;
        }).mapToLong(Long::valueOf).sum();
StructuredLog.info(log);
    }
}
