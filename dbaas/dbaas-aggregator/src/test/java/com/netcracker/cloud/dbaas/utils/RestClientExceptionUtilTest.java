package com.netcracker.cloud.dbaas.utils;

import com.netcracker.cloud.dbaas.exceptions.AdapterException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RestClientExceptionUtilTest {

    @Test
    void testExtractErrorMessage_adapterException_returnsErrorMessage() {
        AdapterException ex = new AdapterException(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), "Internal Server Error");
        assertEquals("Internal Server Error", RestClientExceptionUtil.extractErrorMessage(ex));
    }

    @Test
    void testExtractErrorMessage_wrappedAdapterException_walksChain() {
        AdapterException cause = new AdapterException(Response.Status.SERVICE_UNAVAILABLE.getStatusCode(), "Service Unavailable");
        RuntimeException wrapper = new RuntimeException("outer", cause);
        assertEquals("Service Unavailable", RestClientExceptionUtil.extractErrorMessage(wrapper));
    }

    @Test
    void testExtractErrorMessage_plainException_returnsMessage() {
        RuntimeException ex = new RuntimeException("plain error");
        assertEquals("plain error", RestClientExceptionUtil.extractErrorMessage(ex));
    }

    @Test
    void testExtractErrorMessage_nullThrowable_returnsUnknownError() {
        assertEquals("Unknown error", RestClientExceptionUtil.extractErrorMessage(null));
    }

    @Test
    void testIs4xxError_adapterException404_returnsTrue() {
        assertTrue(RestClientExceptionUtil.is4xxError(new AdapterException(Response.Status.NOT_FOUND.getStatusCode(), "Not Found")));
    }

    @Test
    void testIs4xxError_adapterException400_returnsTrue() {
        assertTrue(RestClientExceptionUtil.is4xxError(new AdapterException(Response.Status.BAD_REQUEST.getStatusCode(), "Bad Request")));
    }

    @Test
    void testIs4xxError_adapterException499_returnsTrue() {
        assertTrue(RestClientExceptionUtil.is4xxError(new AdapterException(499, "Client Error")));
    }

    @Test
    void testIs4xxError_adapterException500_returnsFalse() {
        assertFalse(RestClientExceptionUtil.is4xxError(new AdapterException(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), "Internal Server Error")));
    }

    @Test
    void testIs4xxError_adapterException200_returnsFalse() {
        assertFalse(RestClientExceptionUtil.is4xxError(new AdapterException(Response.Status.OK.getStatusCode(), "OK")));
    }

    @Test
    void testIs4xxError_wrappedAdapterException_walksChain() {
        AdapterException cause = new AdapterException(Response.Status.UNAUTHORIZED.getStatusCode(), "Unauthorized");
        RuntimeException wrapper = new RuntimeException("outer", cause);
        assertTrue(RestClientExceptionUtil.is4xxError(wrapper));
    }

    @Test
    void testIs4xxError_plainException_returnsFalse() {
        assertFalse(RestClientExceptionUtil.is4xxError(new RuntimeException("plain error")));
    }

    @Test
    void testIs4xxError_nullThrowable_returnsFalse() {
        assertFalse(RestClientExceptionUtil.is4xxError(null));
    }
}
