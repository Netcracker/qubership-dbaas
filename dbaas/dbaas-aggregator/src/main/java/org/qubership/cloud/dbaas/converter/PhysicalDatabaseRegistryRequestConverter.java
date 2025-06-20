package org.qubership.cloud.dbaas.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.cloud.dbaas.dto.v3.PhysicalDatabaseRegistryRequestV3;
import jakarta.persistence.AttributeConverter;

import java.io.IOException;

public class PhysicalDatabaseRegistryRequestConverter implements AttributeConverter<PhysicalDatabaseRegistryRequestV3, String> {

    private ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(PhysicalDatabaseRegistryRequestV3 physicalDatabaseRegistryRequestV3) {
        try {
            return objectMapper.writeValueAsString(physicalDatabaseRegistryRequestV3);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public PhysicalDatabaseRegistryRequestV3 convertToEntityAttribute(String dbData) {
        try {
            return objectMapper.readValue(dbData, PhysicalDatabaseRegistryRequestV3.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}

