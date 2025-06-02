package org.qubership.cloud.dbaas.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.cloud.dbaas.entity.pg.Database;
import jakarta.persistence.AttributeConverter;

import java.io.IOException;

public class DatabaseConverter implements AttributeConverter<Database, String> {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(Database attribute) {
        try {
            return objectMapper.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Database convertToEntityAttribute(String dbData) {
        try {
            return objectMapper.readValue(dbData, new TypeReference<Database>() {
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
