package com.netcracker.cloud.dbaas.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcracker.cloud.dbaas.dto.v3.FailureRegistrationV3;
import jakarta.persistence.AttributeConverter;

public class FailureRegistrationConverter implements AttributeConverter<FailureRegistrationV3, String> {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(FailureRegistrationV3 failure) {
        try {
            return failure != null ? objectMapper.writeValueAsString(failure) : null;
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public FailureRegistrationV3 convertToEntityAttribute(String dbData) {
        try {
            return dbData != null ? objectMapper.readValue(dbData, FailureRegistrationV3.class) : null;
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
