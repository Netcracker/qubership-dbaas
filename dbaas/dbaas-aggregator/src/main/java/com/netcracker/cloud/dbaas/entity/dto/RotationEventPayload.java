package com.netcracker.cloud.dbaas.entity.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.SortedMap;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RotationEventPayload {
    private UUID eventId;
    private OffsetDateTime occurredAt;
    private OffsetDateTime previousRotatedAt;
    private SortedMap<String, Object> classifier;
    private String type;
    private String userRole;
}
