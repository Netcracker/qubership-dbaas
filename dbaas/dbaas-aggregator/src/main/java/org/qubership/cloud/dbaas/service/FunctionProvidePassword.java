package org.qubership.cloud.dbaas.service;

@FunctionalInterface
public interface FunctionProvidePassword<Database, String> {
     String apply(Database database, String role);
}
