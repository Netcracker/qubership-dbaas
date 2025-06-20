package org.qubership.cloud.dbaas.logging;

import io.quarkus.logging.LoggingFilter;

import java.util.logging.Filter;
import java.util.logging.LogRecord;
import java.util.regex.Pattern;

@LoggingFilter(name = "dbaas-filter")
public class DbaasLoggingFilter implements Filter {
    private static Pattern FILTER_PATTERN = Pattern.compile("(.* duplicate key value violates unique constraint (.{1}database_registry_classifier_and_type_index.{1}|.{1}classifier_and_type_index.{1}))(\\s.*)");

    @Override
    public boolean isLoggable(LogRecord record) {
        return !FILTER_PATTERN.matcher(record.getMessage()).matches();
    }
}
