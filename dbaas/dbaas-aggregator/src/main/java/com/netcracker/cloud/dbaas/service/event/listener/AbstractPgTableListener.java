package org.qubership.cloud.dbaas.service.event.listener;

import io.quarkus.narayana.jta.QuarkusTransaction;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.PGNotification;
import org.postgresql.jdbc.PgConnection;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public abstract class AbstractPgTableListener implements Runnable {

    protected DataSource dataSource;
    protected Connection conn;
    protected String connectionStatement;
    @Setter
    private long reconnectTimeout = 10000;
    private final AtomicBoolean running = new AtomicBoolean(false);

    protected abstract void reloadH2Cache(UUID id);

    protected abstract String tableName();

    @SneakyThrows
    @Override
    public void run() {
        log.info("run postgresql publish-subscribe notification");
        running.set(true);
        while (running.get()) {
            try {
                if (conn == null) {
                    establishConnection(dataSource.getConnection());
                }
                PgConnection pgConn = unwrapPgConnection();
                PGNotification[] notifications = pgConn.getNotifications(300_000);
                if (notifications != null) {
                    for (org.postgresql.PGNotification notification : notifications) {
                        log.debug("got event from pg table {}. Record id = {}", tableName(), notification.getParameter());
                        UUID id = UUID.fromString(notification.getParameter());
                        QuarkusTransaction.joiningExisting().run(() -> reloadH2Cache(id));
                    }
                }
                // don't remove! This select is needed to support pg connectivity.
                try (Statement stmt = pgConn.createStatement()) {
                    stmt.execute("select 1");
                }
            } catch (SQLException psqlException) {
                Connection conn;
                log.debug("got an error during pg notifying.", psqlException);
                if (!this.conn.isValid(60) || this.conn.isReadOnly()) {
                    while (true) {
                        try {
                            conn = dataSource.getConnection();
                            if (conn.isReadOnly()) {
                                conn.close();
                                log.debug("Connection is read only, wait and reconnect...");
                                Thread.sleep(reconnectTimeout);
                                continue;
                            }
                            this.conn.close(); // this close() should be executed for original Connection, not PgConnection
                            establishConnection(conn);
                            break;
                        } catch (Exception exception) {
                            log.debug("Caught exception while trying to get connection = {}", exception.getMessage());
                            Thread.sleep(reconnectTimeout);
                        }
                    }
                }
            } catch (Exception ex) {
                // debug because we support working without pg in runtime (read only mode) and error level will spam in log
                log.debug("got an error during pg notifying or reloading h2 cache", ex);
            }
        }
    }

    protected void establishConnection(Connection conn) throws SQLException {
        conn.setNetworkTimeout(null, 0);
        if (conn.isWrapperFor(PgConnection.class)) {
            this.conn = conn;
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(connectionStatement);
            }
        }
    }

    private PgConnection unwrapPgConnection() throws SQLException {
        return conn.unwrap(PgConnection.class);
    }

    public void stopListening() {
        running.set(false);
    }
}
