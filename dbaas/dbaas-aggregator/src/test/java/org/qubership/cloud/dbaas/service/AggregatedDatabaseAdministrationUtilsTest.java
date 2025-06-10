package org.qubership.cloud.dbaas.service;

import jakarta.persistence.PersistenceException;

import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.postgresql.util.PSQLState.FOREIGN_KEY_VIOLATION;
import static org.postgresql.util.PSQLState.UNIQUE_VIOLATION;

public class AggregatedDatabaseAdministrationUtilsTest {

    @Test
    public void testIsUniqueViolation_True1() {
        final SQLException sqlException = new SQLException("SQLException without SQLState");
        final PSQLException psqlException = new PSQLException("PSQLException with 23505 SQLState ", UNIQUE_VIOLATION, sqlException);
        final PersistenceException persistenceException = new PersistenceException("PersistenceException", psqlException);
        final boolean isUniqueViolation = AggregatedDatabaseAdministrationService.AggregatedDatabaseAdministrationUtils.isUniqueViolation(persistenceException);
        assertTrue(isUniqueViolation);
    }

    @Test
    public void testIsUniqueViolation_True2() {
        final SQLException sqlException = new SQLException("SQLException without SQLState");
        final PSQLException psqlException1 = new PSQLException("PSQLException with 23505 SQLState ", UNIQUE_VIOLATION, sqlException);
        final PSQLException psqlException2 = new PSQLException("PSQLException with 23503 SQLState ", FOREIGN_KEY_VIOLATION, psqlException1);
        final PersistenceException persistenceException = new PersistenceException("PersistenceException", psqlException2);
        final boolean isUniqueViolation = AggregatedDatabaseAdministrationService.AggregatedDatabaseAdministrationUtils.isUniqueViolation(persistenceException);
        assertTrue(isUniqueViolation);
    }

    @Test
    public void testIsUniqueViolation_False1() {
        final SQLException sqlException = new SQLException("SQLException without SQLState");
        final PSQLException psqlException = new PSQLException("PSQLException with 23503 SQLState ", FOREIGN_KEY_VIOLATION, sqlException);
        final PersistenceException persistenceException = new PersistenceException("PersistenceException", psqlException);
        final boolean isUniqueViolation = AggregatedDatabaseAdministrationService.AggregatedDatabaseAdministrationUtils.isUniqueViolation(persistenceException);
        assertFalse(isUniqueViolation);
    }

    @Test
    public void testIsUniqueViolation_False2() {
        final SQLException sqlException = new SQLException("SQLException with 23503 SQLState", FOREIGN_KEY_VIOLATION.getState());
        final PSQLException psqlException = new PSQLException("PSQLException with 23505 SQLState ", UNIQUE_VIOLATION, sqlException);
        final PersistenceException persistenceException = new PersistenceException("PersistenceException", psqlException);
        final boolean isUniqueViolation = AggregatedDatabaseAdministrationService.AggregatedDatabaseAdministrationUtils.isUniqueViolation(persistenceException);
        assertFalse(isUniqueViolation);
    }

    @Test
    public void testIsUniqueViolation_False3() {
        final PSQLException psqlException = new PSQLException(new ServerErrorMessage("PSQLException without SQLState"));
        final PersistenceException persistenceException = new PersistenceException("PersistenceException", psqlException);
        final boolean isUniqueViolation = AggregatedDatabaseAdministrationService.AggregatedDatabaseAdministrationUtils.isUniqueViolation(persistenceException);
        assertFalse(isUniqueViolation);
    }
}
