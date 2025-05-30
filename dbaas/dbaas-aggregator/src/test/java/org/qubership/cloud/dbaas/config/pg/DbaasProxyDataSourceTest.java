package org.qubership.cloud.dbaas.config.pg;

import org.qubership.cloud.dbaas.JdbcUtils;
import io.agroal.api.AgroalDataSource;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.qubership.cloud.dbaas.config.pg.DbaasProxyDataSource.SQL_STATE_INVALID_CREDENTIALS;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class DbaasProxyDataSourceTest {

    @Mock
    AgroalDataSource dataSource;
    @Mock
    Supplier<String> passwordProvider;

    @Test
    void getConnectionInvalidCreds() throws SQLException {
        try (MockedStatic<JdbcUtils> jdbcUtils = mockStatic(JdbcUtils.class)) {
            jdbcUtils.when(() -> JdbcUtils.createDatasource(any(), any(), any(), anyInt(), any())).thenReturn(dataSource);
            DbaasProxyDataSource dbaasProxyDataSource = new DbaasProxyDataSource(null, null, passwordProvider, 15, null);
            String password = "test-password";
            Connection expectedConnection = Mockito.mock(Connection.class);
            AtomicInteger counter = new AtomicInteger();
            when(passwordProvider.get()).thenReturn(password);
            when(dataSource.getConnection()).then(i -> {
                if (counter.getAndIncrement() == 0) {
                    throw new SQLException("reason", SQL_STATE_INVALID_CREDENTIALS, new Exception("cause"));
                } else {
                    return expectedConnection;
                }
            });
            final Connection connection = dbaasProxyDataSource.getConnection();
            Assertions.assertEquals(expectedConnection, connection);
            verify(passwordProvider, times(2)).get();
            jdbcUtils.verify(() -> JdbcUtils.updatePassword(dataSource, passwordProvider.get()), times(1));
        }
    }

    @Test
    void getConnectionOtherSqlException() throws SQLException {
        try (MockedStatic<JdbcUtils> jdbcUtils = mockStatic(JdbcUtils.class)) {
            jdbcUtils.when(() -> JdbcUtils.createDatasource(any(), any(), any(), anyInt(), any())).thenReturn(dataSource);
            DbaasProxyDataSource dbaasProxyDataSource = new DbaasProxyDataSource(null, null, passwordProvider, 15, null);
            final SQLException sqlException = new SQLException("reason", "another sql state", new Exception("cause"));
            when(dataSource.getConnection()).then(i -> {
                throw sqlException;
            });
            Assertions.assertThrows(SQLException.class, () -> {
                dbaasProxyDataSource.getConnection();
            }, sqlException.getMessage());
        }
    }

    @Test
    void getConnectionUserPasswordInvalidCreds() throws SQLException {
        try (MockedStatic<JdbcUtils> jdbcUtils = mockStatic(JdbcUtils.class)) {
            jdbcUtils.when(() -> JdbcUtils.createDatasource(any(), any(), any(), anyInt(), any())).thenReturn(dataSource);
            DbaasProxyDataSource dbaasProxyDataSource = new DbaasProxyDataSource(null, null, passwordProvider, 15, null);
            String username = "test-username";
            String password = "test-password";
            Connection expectedConnection = Mockito.mock(Connection.class);
            AtomicInteger counter = new AtomicInteger();
            when(passwordProvider.get()).thenReturn(password);
            when(dataSource.getConnection(username, password)).then(i -> {
                if (counter.getAndIncrement() == 0) {
                    throw new SQLException("reason", SQL_STATE_INVALID_CREDENTIALS, new Exception("cause"));
                } else {
                    return expectedConnection;
                }
            });
            final Connection connection = dbaasProxyDataSource.getConnection(username, password);
            Assertions.assertEquals(expectedConnection, connection);
            verify(passwordProvider, times(2)).get();
            jdbcUtils.verify(() -> JdbcUtils.updatePassword(dataSource, passwordProvider.get()), times(1));
        }
    }

    @Test
    void getConnectionUserPasswordOtherSqlException() throws SQLException {
        try (MockedStatic<JdbcUtils> jdbcUtils = mockStatic(JdbcUtils.class)) {
            jdbcUtils.when(() -> JdbcUtils.createDatasource(any(), any(), any(), anyInt(), any())).thenReturn(dataSource);
            DbaasProxyDataSource dbaasProxyDataSource = new DbaasProxyDataSource(null, null, passwordProvider, 15, null);
            String username = "test-username";
            String password = "test-password";
            final SQLException sqlException = new SQLException("reason", "another sql state", new Exception("cause"));
            when(dataSource.getConnection(username, password)).then(i -> {
                throw sqlException;
            });
            Assertions.assertThrows(SQLException.class, () -> {
                dbaasProxyDataSource.getConnection(username, password);
            }, sqlException.getMessage());
        }
    }
}
