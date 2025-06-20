package org.qubership.cloud.dbaas.service.monitoring;

import org.qubership.cloud.dbaas.DatabaseType;
import org.qubership.cloud.dbaas.connections.handlers.CassandraConnectionHandler;
import org.qubership.cloud.dbaas.connections.handlers.ConnectionHandler;
import org.qubership.cloud.dbaas.connections.handlers.ConnectionHandlerFactory;
import org.qubership.cloud.dbaas.dto.role.Role;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.qubership.cloud.dbaas.Constants.ROLE;

class ConnectionHandlerFactoryTest {

    String expectedHost = "some-host-1";
    String expectedContactPoints = "point1,point2";

    @Test
    void test_ConnectionHandlerByType() {
        ConnectionHandlerFactory factory = new ConnectionHandlerFactory(Arrays.asList(new CassandraConnectionHandler()));

        Map<String, Object> connectionProperties1 = new HashMap<>();
        connectionProperties1.put("host", "some-host-1");
        connectionProperties1.put("contactPoints", Arrays.asList("point1", "point2"));
        connectionProperties1.put(ROLE, Role.ADMIN.toString());

        Map<String, Object> connectionProperties2 = new HashMap<>();
        connectionProperties2.put("host", "ex-some-host-1");
        connectionProperties2.put("contactPoints", Arrays.asList("ex-point1", "ex-point2"));
        connectionProperties2.put(ROLE, "ex-admin");

        List<Map<String, Object>> list = Arrays.asList(connectionProperties1, connectionProperties2);

        ConnectionHandler getter = factory.getConnectionHandler("some_type");
        Assertions.assertEquals(expectedHost, getter.getHost(list).orElse(null));

        ConnectionHandler postgresqlConnectionHandler = factory.getConnectionHandler(DatabaseType.POSTGRESQL.toString());
        Assertions.assertEquals(expectedHost, postgresqlConnectionHandler.getHost(list).orElse(null));

        ConnectionHandler cassandraConnectionHandler = factory.getConnectionHandler(DatabaseType.CASSANDRA.toString());
        Assertions.assertEquals(expectedContactPoints, cassandraConnectionHandler.getHost(list).orElse(null));
    }

    @Test
    void test_ConnectionHandlerByType_NoHostAndContactsInConnections() {
        ConnectionHandlerFactory factory = new ConnectionHandlerFactory(Arrays.asList(new CassandraConnectionHandler()));

        Map<String, Object> connectionProperties1 = new HashMap<>();
        connectionProperties1.put(ROLE, Role.ADMIN.toString());

        Map<String, Object> connectionProperties2 = new HashMap<>();
        connectionProperties2.put(ROLE, "ex-admin");

        List<Map<String, Object>> list = Arrays.asList(connectionProperties1, connectionProperties2);

        ConnectionHandler getter = factory.getConnectionHandler("some_type");
        Assertions.assertNull(getter.getHost(list).orElse(null));

        ConnectionHandler postgresqlConnectionHandler = factory.getConnectionHandler(DatabaseType.POSTGRESQL.toString());
        Assertions.assertNull(postgresqlConnectionHandler.getHost(list).orElse(null));

        ConnectionHandler cassandraConnectionHandler = factory.getConnectionHandler(DatabaseType.CASSANDRA.toString());
        Assertions.assertNull(cassandraConnectionHandler.getHost(list).orElse(null));
    }

    @Test
    void test_ConnectionHandlerByType_WithEmptyConnectionPropMap() {
        ConnectionHandlerFactory factory = new ConnectionHandlerFactory(Arrays.asList(new CassandraConnectionHandler()));
        List<Map<String, Object>> list = Arrays.asList();

        ConnectionHandler getter = factory.getConnectionHandler("some_type");
        Assertions.assertNull(getter.getHost(list).orElse(null));
        ConnectionHandler postgresqlConnectionHandler = factory.getConnectionHandler(DatabaseType.POSTGRESQL.toString());
        Assertions.assertNull(postgresqlConnectionHandler.getHost(list).orElse(null));
        ConnectionHandler cassandraConnectionHandler = factory.getConnectionHandler(DatabaseType.CASSANDRA.toString());
        Assertions.assertNull(cassandraConnectionHandler.getHost(list).orElse(null));
    }
}