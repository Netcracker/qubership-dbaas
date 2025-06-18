package org.qubership.cloud.dbaas.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.cloud.dbaas.dto.RuleOnMicroservice;

import jakarta.persistence.AttributeConverter;
import java.io.IOException;
import java.util.List;

public class ListRuleOnMicroserviceConverter implements AttributeConverter<List<RuleOnMicroservice>, String> {

    private ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(List<RuleOnMicroservice> attribute) {
        try {
            return objectMapper.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<RuleOnMicroservice> convertToEntityAttribute(String dbData) {
        try {
            return objectMapper.readValue(dbData, new TypeReference<List<RuleOnMicroservice>>(){});
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}

