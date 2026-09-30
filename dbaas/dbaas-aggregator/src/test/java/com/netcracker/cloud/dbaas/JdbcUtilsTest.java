package com.netcracker.cloud.dbaas;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcUtilsTest {

    private static final String PLAIN_URL = "jdbc:postgresql://pg-patroni.postgres:5432/dbaas?connectTimeout=10&socketTimeout=30";
    private static final String SSL_PARAMS = "&ssl=true&sslfactory=org.postgresql.ssl.SingleCertValidatingFactory&sslfactoryarg=file://";

    private static String resolve(Map<String, String> parameters) {
        Map<String, String> all = new HashMap<>(Map.of(
                "POSTGRES_HOST", "pg-patroni.postgres",
                "POSTGRES_PORT", "5432",
                "POSTGRES_DATABASE", "dbaas"));
        all.putAll(parameters);
        return JdbcUtils.resolveConnectionURL(all::get);
    }

    @Test
    void testResolveConnectionURL_postgresTlsExplicitlyEnabled() {
        String url = resolve(Map.of(JdbcUtils.POSTGRES_TLS_ENABLED, "true"));

        assertEquals(PLAIN_URL + SSL_PARAMS + "/etc/tls/ca.crt", url);
    }

    @Test
    void testResolveConnectionURL_postgresTlsDisabledWhileInboundTlsEnabled() {
        String url = resolve(Map.of(
                JdbcUtils.POSTGRES_TLS_ENABLED, "false",
                JdbcUtils.LEGACY_INTERNAL_TLS_ENABLED, "true"));

        assertEquals(PLAIN_URL, url);
        assertFalse(url.contains("ssl=true"));
    }

    @Test
    void testResolveConnectionURL_legacyInternalTlsFallbackWhenPostgresTlsUnset() {
        String url = resolve(Map.of(JdbcUtils.LEGACY_INTERNAL_TLS_ENABLED, "true"));

        assertEquals(PLAIN_URL + SSL_PARAMS + "/etc/tls/ca.crt", url);
    }

    @Test
    void testResolveConnectionURL_customPostgresCaPath() {
        String url = resolve(Map.of(
                JdbcUtils.POSTGRES_TLS_ENABLED, "true",
                JdbcUtils.POSTGRES_TLS_CA_CERT_PATH, "/etc/postgres-tls/root.crt"));

        assertEquals(PLAIN_URL + SSL_PARAMS + "/etc/postgres-tls/root.crt", url);
    }

    @Test
    void testResolveConnectionURL_caPathFollowsCertificateFilePathWhenPostgresCaPathUnset() {
        String url = resolve(Map.of(
                JdbcUtils.POSTGRES_TLS_ENABLED, "true",
                "CERTIFICATE_FILE_PATH", "/etc/custom-tls"));

        assertTrue(url.endsWith("sslfactoryarg=file:///etc/custom-tls/ca.crt"), url);
    }

    @Test
    void testResolveConnectionURL_nonTlsUrlUnchangedWhenNoTlsParameterSet() {
        assertEquals(PLAIN_URL, resolve(Map.of()));
    }

    @Test
    void testResolveConnectionURL_explicitPostgresTlsIgnoresSurroundingWhitespace() {
        String url = resolve(Map.of(JdbcUtils.POSTGRES_TLS_ENABLED, " true \n"));

        assertTrue(url.contains("&ssl=true"), url);
    }
}
