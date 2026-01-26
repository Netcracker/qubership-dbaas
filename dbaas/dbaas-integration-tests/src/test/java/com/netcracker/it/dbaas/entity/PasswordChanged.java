package com.netcracker.it.dbaas.entity;

import lombok.Data;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.Map;

@Data
public class PasswordChanged {
    @NonNull
    private Map<String, Object> classifier;
    @NonNull
    private Map<String, Object> connection;
}