package org.qubership.cloud.dbaas.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.cloud.dbaas.dto.HttpBasicCredentials;

import jakarta.persistence.AttributeConverter;
import java.io.IOException;

public class HttpBasicCredentialsConverter implements AttributeConverter<HttpBasicCredentials, String> {

    private ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(HttpBasicCredentials attribute) {
        try {
            return objectMapper.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public HttpBasicCredentials convertToEntityAttribute(String dbData) {
        try {
            return objectMapper.readValue(dbData, HttpBasicCredentials.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
