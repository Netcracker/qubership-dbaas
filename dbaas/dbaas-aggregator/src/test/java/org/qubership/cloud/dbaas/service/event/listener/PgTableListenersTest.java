package org.qubership.cloud.dbaas.service.event.listener;

import io.agroal.api.AgroalDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.postgresql.PGNotification;
import org.postgresql.jdbc.PgConnection;
import org.postgresql.util.PSQLException;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseDbaasRepository;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import org.qubership.cloud.dbaas.repositories.dbaas.PhysicalDatabaseDbaasRepository;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.qubership.cloud.dbaas.mock.MockedQuarkusTransactionRunnableWrapper.withStaticMocks;

class PgTableListenersTest {

    @Mock
    DatabaseDbaasRepository databaseDbaasRepository;
    @Mock
    PhysicalDatabaseDbaasRepository physicalDatabaseDbaasRepository;
    @Mock
    DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;

    private final Object mutex = new Object();
    private AbstractPgTableListener currentListener;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        Mockito.doNothing().when(databaseDbaasRepository).reloadH2Cache(Mockito.any());
        Mockito.doNothing().when(databaseDbaasRepository).reloadH2Cache();
        Mockito.doReturn(mutex).when(databaseDbaasRepository).getMutex();
        Mockito.doNothing().when(physicalDatabaseDbaasRepository).reloadH2Cache(Mockito.any());
        Mockito.doNothing().when(physicalDatabaseDbaasRepository).reloadH2Cache();
        Mockito.doReturn(mutex).when(physicalDatabaseDbaasRepository).getMutex();
        Mockito.doNothing().when(databaseRegistryDbaasRepository).reloadDatabaseRegistryH2Cache(Mockito.any());
        Mockito.doReturn(mutex).when(databaseRegistryDbaasRepository).getMutex();
    }

    @AfterEach
    public void cleanup() {
        currentListener.stopListening();
    }

    @Test
    void databaseTable_notificationTest() throws SQLException {
        getNotificationTest(datasource -> new PgDatabaseTableListener(datasource, databaseDbaasRepository));
        verify(databaseDbaasRepository, timeout(1000)).reloadH2Cache(Mockito.any());
    }

    @Test
    void databaseTable_stopListeningTest() throws SQLException, InterruptedException {
        stopListeningTest(datasource -> new PgDatabaseTableListener(datasource, databaseDbaasRepository));
        verify(databaseDbaasRepository, timeout(1000)).reloadH2Cache(Mockito.any());
    }

    @Test
    void databaseTable_roPgStartupTest() throws SQLException {
        roPgStartupTest(datasource -> new PgDatabaseTableListener(datasource, databaseDbaasRepository));
        verify(databaseDbaasRepository, timeout(1000)).reloadH2Cache(Mockito.any());
    }

    @Test
    void physicalDatabaseTable_notificationTest() throws SQLException {
        getNotificationTest(datasource -> new PgPhysicalDatabaseTableListener(datasource, physicalDatabaseDbaasRepository));
        verify(physicalDatabaseDbaasRepository, timeout(1000)).reloadH2Cache(Mockito.any());
    }

    @Test
    void physicalDatabaseTable_stopListeningTest() throws SQLException, InterruptedException {
        stopListeningTest(datasource -> new PgPhysicalDatabaseTableListener(datasource, physicalDatabaseDbaasRepository));
        verify(physicalDatabaseDbaasRepository, timeout(1000)).reloadH2Cache(Mockito.any());
    }

    @Test
    void physicalDatabaseTable_roPgStartupTest() throws SQLException {
        roPgStartupTest(datasource -> new PgPhysicalDatabaseTableListener(datasource, physicalDatabaseDbaasRepository));
        verify(physicalDatabaseDbaasRepository, timeout(1000)).reloadH2Cache(Mockito.any());
    }

    @Test
    void classifierTable_notificationTest() throws SQLException {
        getNotificationTest(datasource -> new PgClassifierTableListener(datasource, databaseRegistryDbaasRepository));
        verify(databaseRegistryDbaasRepository, timeout(1000)).reloadDatabaseRegistryH2Cache(Mockito.any());
    }

    @Test
    void classifierTable_stopListeningTest() throws SQLException, InterruptedException {
        stopListeningTest(datasource -> new PgClassifierTableListener(datasource, databaseRegistryDbaasRepository));
        verify(databaseRegistryDbaasRepository, timeout(1000)).reloadDatabaseRegistryH2Cache(Mockito.any());
    }

    @Test
    void classifierTable_roPgStartupTest() throws SQLException {
        roPgStartupTest(datasource -> new PgClassifierTableListener(datasource, databaseRegistryDbaasRepository));
        verify(databaseRegistryDbaasRepository, timeout(1000)).reloadDatabaseRegistryH2Cache(Mockito.any());
    }

    void getNotificationTest(Function<AgroalDataSource, AbstractPgTableListener> listenerCreator) throws SQLException {
        PgConnection pgConnection = Mockito.mock(PgConnection.class);
        Mockito.when(pgConnection.createStatement()).thenReturn(Mockito.mock(Statement.class));

        PGNotification pgNotificationMock = Mockito.mock(PGNotification.class);
        Mockito.when(pgNotificationMock.getParameter()).thenReturn(UUID.randomUUID().toString());
        PGNotification[] pgNotifications = {pgNotificationMock};

        Connection connectionWrapper = Mockito.mock(Connection.class);
        Mockito.when(connectionWrapper.unwrap(PgConnection.class)).thenReturn(pgConnection);
        Mockito.when(connectionWrapper.createStatement()).thenReturn(Mockito.mock(Statement.class));
        Mockito.when(connectionWrapper.isWrapperFor(PgConnection.class)).thenReturn(true);

        AgroalDataSource datasource = Mockito.mock(AgroalDataSource.class);
        Mockito.when(datasource.getConnection()).thenReturn(connectionWrapper);

        Mockito.when(pgConnection.getNotifications(300_000)).thenReturn(pgNotifications).then(invocation ->
        {
            Thread.sleep(300);
            return null;
        });

        currentListener = listenerCreator.apply(datasource);

        new Thread(withStaticMocks(currentListener)).start();
    }

    void stopListeningTest(Function<AgroalDataSource, AbstractPgTableListener> listenerCreator) throws SQLException, InterruptedException {
        PgConnection pgConnection = Mockito.mock(PgConnection.class);
        Mockito.when(pgConnection.createStatement()).thenReturn(Mockito.mock(Statement.class));

        PGNotification pgNotificationMock = Mockito.mock(PGNotification.class);
        Mockito.when(pgNotificationMock.getParameter()).thenReturn(UUID.randomUUID().toString());
        PGNotification[] pgNotifications = {pgNotificationMock};

        Connection connectionWrapper = Mockito.mock(Connection.class);
        Mockito.when(connectionWrapper.unwrap(PgConnection.class)).thenReturn(pgConnection);
        Mockito.when(connectionWrapper.createStatement()).thenReturn(Mockito.mock(Statement.class));
        Mockito.when(connectionWrapper.isWrapperFor(PgConnection.class)).thenReturn(true);

        AgroalDataSource datasource = Mockito.mock(AgroalDataSource.class);
        Mockito.when(datasource.getConnection()).thenReturn(connectionWrapper);

        Mockito.when(pgConnection.getNotifications(300_000)).then(invocation ->
        {
            Thread.sleep(300);
            return pgNotifications;
        });

        currentListener = listenerCreator.apply(datasource);

        new Thread(withStaticMocks(currentListener)).start();
        Thread.sleep(200);
        currentListener.stopListening();
        Thread.sleep(400);
        verify(pgConnection, Mockito.atLeastOnce()).getNotifications(300_000);
    }

    void roPgStartupTest(Function<AgroalDataSource, AbstractPgTableListener> listenerCreator) throws SQLException {
        PgConnection roConnection = Mockito.mock(PgConnection.class);
        Mockito.when(roConnection.isWrapperFor(PgConnection.class)).thenReturn(true);
        Mockito.when(roConnection.isValid(anyInt())).thenReturn(true);
        Mockito.when(roConnection.isReadOnly()).thenReturn(true);
        Statement roStatement = Mockito.mock(Statement.class);
        Mockito.when(roConnection.createStatement()).thenReturn(roStatement);
        Mockito.when(roStatement.execute(anyString())).thenThrow(new PSQLException("", null, null));

        PgConnection rwConnection = Mockito.mock(PgConnection.class);
        Mockito.when(rwConnection.isWrapperFor(PgConnection.class)).thenReturn(true);
        Mockito.when(rwConnection.unwrap(PgConnection.class)).thenReturn(rwConnection);
        Mockito.when(rwConnection.isValid(anyInt())).thenReturn(true);
        Mockito.when(rwConnection.isReadOnly()).thenReturn(false);
        Statement rwStatement = Mockito.mock(Statement.class);
        Mockito.when(rwConnection.createStatement()).thenReturn(rwStatement);
        PGNotification pgNotificationMock = Mockito.mock(PGNotification.class);
        Mockito.when(pgNotificationMock.getParameter()).thenReturn(UUID.randomUUID().toString());
        PGNotification[] pgNotifications = {pgNotificationMock};
        Mockito.when(rwConnection.getNotifications(anyInt())).thenReturn(pgNotifications).then(invocation ->
        {
            Thread.sleep(300);
            return null;
        });

        AgroalDataSource datasource = Mockito.mock(AgroalDataSource.class);
        AtomicInteger connectionAttempt = new AtomicInteger();
        Mockito.when(datasource.getConnection()).then(inv -> {
                    if (connectionAttempt.getAndIncrement() < 2)
                        return roConnection;
                    else
                        return rwConnection;
                }
        );

        currentListener = listenerCreator.apply(datasource);
        currentListener.setReconnectTimeout(100);

        new Thread(withStaticMocks(currentListener)).start();
    }
}
