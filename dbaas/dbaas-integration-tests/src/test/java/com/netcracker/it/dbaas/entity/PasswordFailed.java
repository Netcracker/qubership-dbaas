package com.netcracker.it.dbaas.entity;

import lombok.Data;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.Map;

@Data
public class PasswordFailed {
    @NonNull
    private Map<String, Object> classifier;
    @NonNull
    private String message;
}