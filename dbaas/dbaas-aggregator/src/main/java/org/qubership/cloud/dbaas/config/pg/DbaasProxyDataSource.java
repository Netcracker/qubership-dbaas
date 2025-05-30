package org.qubership.cloud.dbaas.config.pg;

import org.qubership.cloud.dbaas.JdbcUtils;
import io.agroal.api.AgroalDataSource;
import io.agroal.api.AgroalDataSourceMetrics;
import io.agroal.api.AgroalPoolInterceptor;
import io.agroal.api.configuration.AgroalDataSourceConfiguration;
import io.agroal.api.transaction.TransactionIntegration;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static org.qubership.cloud.dbaas.JdbcUtils.updatePassword;

@Slf4j
public class DbaasProxyDataSource implements AgroalDataSource {
    public static final String SQL_STATE_INVALID_CREDENTIALS = "28P01";

    private String url;
    private String user;
    private Supplier<String> passwordProvider;
    private AgroalDataSource dataSource;

    @SneakyThrows
    public DbaasProxyDataSource(String url, String user, Supplier<String> passwordProvider, int maxPoolSize, TransactionIntegration transactionIntegration) {
        this.url = url;
        this.user = user;
        this.passwordProvider = passwordProvider;
        dataSource = JdbcUtils.createDatasource(url, user, passwordProvider.get(), maxPoolSize, transactionIntegration);
    }

    private AgroalDataSource getDatasource() {
        return dataSource;
    }

    protected Connection withPasswordCheck(Callable<Connection> connectionProvider) throws Exception {
        try {
            return connectionProvider.call();
        } catch (SQLException ex) {
            if (SQL_STATE_INVALID_CREDENTIALS.equalsIgnoreCase(ex.getSQLState())) { // invalid password
                log.info("DB password has expired try to get a new one");
                updatePassword(dataSource, passwordProvider.get());
                return connectionProvider.call();
            } else {
                log.error("Can not get DB.", ex);
                throw ex;
            }
        }
    }

    @Override
    public AgroalDataSourceConfiguration getConfiguration() {
        return getDatasource().getConfiguration();
    }

    @Override
    public AgroalDataSourceMetrics getMetrics() {
        return getDatasource().getMetrics();
    }

    @Override
    public void flush(FlushMode flushMode) {
        getDatasource().flush(flushMode);
    }

    @Override
    public void setPoolInterceptors(Collection<? extends AgroalPoolInterceptor> collection) {
        getDatasource().setPoolInterceptors(collection);
    }

    @Override
    public List<AgroalPoolInterceptor> getPoolInterceptors() {
        return getDatasource().getPoolInterceptors();
    }

    @Override
    public void close() {
        getDatasource().close();
    }

    @SneakyThrows
    @Override
    public Connection getConnection() throws SQLException {
        return withPasswordCheck(() -> getDatasource().getConnection());
    }

    @SneakyThrows
    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return withPasswordCheck(() -> getDatasource().getConnection(username, password));
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return getDatasource().unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return getDatasource().isWrapperFor(iface);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return getDatasource().getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        getDatasource().setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        getDatasource().setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return getDatasource().getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return getDatasource().getParentLogger();
    }
}
