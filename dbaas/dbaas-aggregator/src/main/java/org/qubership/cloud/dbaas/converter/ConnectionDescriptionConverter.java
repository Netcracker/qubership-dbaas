package org.qubership.cloud.dbaas.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.cloud.dbaas.dto.ConnectionDescription;

import jakarta.persistence.AttributeConverter;
import java.io.IOException;

public class ConnectionDescriptionConverter implements AttributeConverter<ConnectionDescription, String> {

    private ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(ConnectionDescription attribute) {
        try {
            return objectMapper.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public ConnectionDescription convertToEntityAttribute(String dbData) {
        try {
            return objectMapper.readValue(dbData, ConnectionDescription.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
