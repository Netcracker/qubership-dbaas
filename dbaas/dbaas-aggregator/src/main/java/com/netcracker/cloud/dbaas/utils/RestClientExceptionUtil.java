package com.netcracker.cloud.dbaas.utils;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

public final class RestClientExceptionUtil {

    private RestClientExceptionUtil() {
    }

    public static String extractErrorMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause != null) {
            if (cause instanceof WebApplicationException webEx) {
                Response response = webEx.getResponse();
                try {
                    return response.readEntity(String.class);
                } catch (Exception readEx) {
                    return "Unable to read response body: " + readEx.getMessage();
                }
            }
            cause = cause.getCause();
        }
        return throwable != null ? throwable.getMessage() : "Unknown error";
    }

    public static boolean is4xxError(Throwable throwable) {
        Throwable cause = throwable;

        while (cause != null) {
            if (cause instanceof WebApplicationException ex) {
                Response res = ex.getResponse();
                return 400 <= res.getStatus() && res.getStatus() < 500;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
