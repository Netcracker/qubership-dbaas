package com.netcracker.cloud.dbaas.utils;

import com.netcracker.cloud.dbaas.exceptions.AdapterException;

public final class RestClientExceptionUtil {

    private RestClientExceptionUtil() {
    }

    public static String extractErrorMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause != null) {
            if (cause instanceof AdapterException e) {
                return e.getErrorMessage();
            }
            cause = cause.getCause();
        }
        return throwable != null ? throwable.getMessage() : "Unknown error";
    }

    public static boolean is4xxError(Throwable throwable) {
        Throwable cause = throwable;
        while (cause != null) {
            if (cause instanceof AdapterException e) {
                return e.getHttpCode() >= 400 && e.getHttpCode() < 500;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
