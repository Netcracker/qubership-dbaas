package org.qubership.cloud.dbaas.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.cloud.dbaas.dto.v3.ApiVersion;
import jakarta.persistence.AttributeConverter;

import java.io.IOException;

public class ApiVersionConverter implements AttributeConverter<ApiVersion, String> {

    private ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(ApiVersion attribute) {
        try {
            return objectMapper.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public ApiVersion convertToEntityAttribute(String dbData) {
        try {
            return dbData != null ? objectMapper.readValue(dbData, new TypeReference<ApiVersion>() {}) : null;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
