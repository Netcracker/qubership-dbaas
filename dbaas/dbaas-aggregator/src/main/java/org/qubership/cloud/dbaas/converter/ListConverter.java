package org.qubership.cloud.dbaas.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

public class ListConverter implements AttributeConverter<List<Object>, String> {

    private ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(List<Object> attribute) {
        try {
            return objectMapper.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<Object> convertToEntityAttribute(String dbData) {
        try {
            if (dbData != null) {
                return objectMapper.readValue(dbData, new TypeReference<List<Object>>() {
                });
            } else {
                return Collections.emptyList();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
