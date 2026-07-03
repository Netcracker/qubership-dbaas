package com.netcracker.cloud.dbaas.logging;

import org.jboss.logmanager.MDC;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Emits short-lived MDC fields for Quarkus JSON console logging.
 * Field keys must be snake_case; values are stringified for JSON safety.
 */
public final class StructuredLog {

    private StructuredLog() {
    }

    public static void trace(Logger logger, String message, Object... fields) {
        withFields(logger, message, fields, (l, m) -> l.trace(m));
    }

    public static void debug(Logger logger, String message, Object... fields) {
        withFields(logger, message, fields, (l, m) -> l.debug(m));
    }

    public static void info(Logger logger, String message, Object... fields) {
        withFields(logger, message, fields, (l, m) -> l.info(m));
    }

    public static void warn(Logger logger, String message, Object... fields) {
        withFields(logger, message, fields, (l, m) -> l.warn(m));
    }

    public static void warn(Logger logger, String message, Throwable throwable, Object... fields) {
        withFields(logger, message, fields, (l, m) -> l.warn(m, throwable));
    }

    public static void error(Logger logger, String message, Object... fields) {
        withFields(logger, message, fields, (l, m) -> l.error(m));
    }

    public static void error(Logger logger, String message, Throwable throwable, Object... fields) {
        withFields(logger, message, fields, (l, m) -> l.error(m, throwable));
    }

    @FunctionalInterface
    private interface LogAction {
        void log(Logger logger, String message);
    }

    private static void withFields(Logger logger, String message, Object[] fields, LogAction action) {
        List<String> keys = putFields(fields);
        try {
            action.log(logger, message);
        } finally {
            clearFields(keys);
        }
    }

    private static List<String> putFields(Object[] fields) {
        List<String> keys = new ArrayList<>();
        if (fields == null) {
            return keys;
        }
        for (int i = 0; i + 1 < fields.length; i += 2) {
            String key = String.valueOf(fields[i]);
            Object value = fields[i + 1];
            MDC.put(key, stringify(value));
            keys.add(key);
        }
        return keys;
    }

    private static void clearFields(List<String> keys) {
        for (String key : keys) {
            MDC.remove(key);
        }
    }

    static String stringify(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Throwable throwable) {
            return throwable.toString();
        }
        return String.valueOf(value);
    }
}
