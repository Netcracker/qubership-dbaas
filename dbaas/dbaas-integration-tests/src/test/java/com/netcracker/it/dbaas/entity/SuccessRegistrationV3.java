package com.netcracker.it.dbaas.entity;

import lombok.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class SuccessRegistrationV3 {
    @NonNull
    private UUID id;
    @NonNull
    private List<Map<String,Object>> connectionProperties;
    @NonNull
    private List<DbResource> resources;

}