package com.netcracker.cloud.dbaas.serializer;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.DefaultBaseTypeLimitingValidator;
import io.quarkus.jackson.ObjectMapperCustomizer;
import jakarta.inject.Singleton;

@Singleton
public class SubtypesObjectMapperCustomizer implements ObjectMapperCustomizer {

    @Override
    public void customize(ObjectMapper objectMapper) {
        objectMapper.setPolymorphicTypeValidator(new DefaultBaseTypeLimitingValidator());
    }
}
