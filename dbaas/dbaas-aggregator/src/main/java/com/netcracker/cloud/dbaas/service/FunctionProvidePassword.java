package com.netcracker.cloud.dbaas.service;

@FunctionalInterface
public interface FunctionProvidePassword<Database, String> {
     String apply(Database database, String role);
}
