package org.qubership.cloud.dbaas.connections.handlers;

import org.qubership.cloud.dbaas.DatabaseType;
import io.quarkus.arc.All;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ConnectionHandlerFactory {

    private Map<DatabaseType, ConnectionHandler> handlers;
    final private ConnectionHandler defaultConnectionHandler;

    @Inject
    public ConnectionHandlerFactory(@All List<ConnectionHandler> connHandler) {
        handlers = new HashMap<>();
        if (connHandler != null) {
            connHandler.forEach(connectionHandler -> handlers.put(connectionHandler.getPhysDbType(), connectionHandler));
        }
        defaultConnectionHandler = new DefaultConnectionHandler();
    }

    public ConnectionHandler getConnectionHandler(String databaseType) {
        DatabaseType type = DatabaseType.fromString(databaseType);
        ConnectionHandler connectionHandler = handlers.get(type);
        if (connectionHandler == null) {
            return new DefaultConnectionHandler();
        } else {
            return connectionHandler;
        }
    }
}
