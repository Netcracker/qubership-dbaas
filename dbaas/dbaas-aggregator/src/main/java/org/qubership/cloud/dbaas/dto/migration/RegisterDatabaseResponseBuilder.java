package org.qubership.cloud.dbaas.dto.migration;

import org.qubership.cloud.dbaas.dto.v3.DatabaseResponseV3ListCP;
import jakarta.ws.rs.core.Response;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static jakarta.ws.rs.core.Response.Status.CONFLICT;
import static jakarta.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;


@Slf4j
public class RegisterDatabaseResponseBuilder {
    private Map<String, MigrationResult> body = new HashMap<>();
    private Boolean isFailed = false;
    private Boolean isConflicted = false;

    public void addMigratedDb(String dbName, String type) {
        MigrationResult migrationResult = getMigrationResult(type);
        migrationResult.getMigrated().add(dbName);
    }

    public void addMigratedDb(DatabaseResponseV3ListCP databaseResponse) {
        MigrationResult migrationResult = getMigrationResult(databaseResponse.getType());
        migrationResult.getMigratedDbInfo().add(databaseResponse);
        addMigratedDb(databaseResponse.getName(), databaseResponse.getType());
    }

    public void addConflictedDb(String dbName, String type) {
        MigrationResult migrationResult = getMigrationResult(type);
        migrationResult.getConflicted().add(dbName);
        isConflicted = true;
    }

    public void addFailedDb(String dbName, String type) {
        MigrationResult migrationResult = getMigrationResult(type);
        migrationResult.getFailed().add(dbName);
        isFailed = true;
    }

    public void addFailureReason(String dbName, String type, String reason) {
        MigrationResult migrationResult = getMigrationResult(type);
        migrationResult.getFailureReasons().add(dbName + " failed due to: " + reason);
    }

    public Response buildAndResponse() {
        if (isFailed) {
            return Response.status(INTERNAL_SERVER_ERROR).entity(body).build();
        } else if (isConflicted) {
            return Response.status(CONFLICT).entity(body).build();
        } else {
            return Response.ok(body).build();
        }
    }


    private MigrationResult getMigrationResult(String type) {
        MigrationResult migrationResult = body.get(type);
        if (migrationResult == null) {
            migrationResult = new MigrationResult();
            body.put(type, migrationResult);
        }
        return migrationResult;
    }


    @Data
    public static class MigrationResult {
        List<String> migrated = new ArrayList<>();
        List<DatabaseResponseV3ListCP> migratedDbInfo = new ArrayList<>();
        List<String> conflicted = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<String> failureReasons = new ArrayList<>();
    }
}
