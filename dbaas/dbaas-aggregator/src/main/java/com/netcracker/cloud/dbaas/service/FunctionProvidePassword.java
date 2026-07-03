package com.netcracker.cloud.dbaas.service;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

@FunctionalInterface
public interface FunctionProvidePassword<Database, String> {
     String apply(Database database, String role);
}
