package org.qubership.cloud.dbaas.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.qubership.cloud.dbaas.entity.pg.backupV2.LogicalBackupStatus;

@Converter(autoApply = false)
public class LogicalBackupStatusConverter implements AttributeConverter<LogicalBackupStatus, String> {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(LogicalBackupStatus status) {
        if (status == null) return null;
        try {
            return objectMapper.writeValueAsString(status);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Error converting LogicalBackupStatus to JSON", e);
        }
    }

    @Override
    public LogicalBackupStatus convertToEntityAttribute(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, LogicalBackupStatus.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Error reading LogicalBackupStatus from JSON", e);
        }
    }
}

