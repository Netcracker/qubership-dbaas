package com.netcracker.it.dbaas.entity;

import lombok.*;

import java.util.Map;


@EqualsAndHashCode
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PhysicalDatabaseRegistryRequestV3{
    private String adapterAddress;
    private HttpBasicCredentials httpBasicCredentials;
    private Map<String, String> labels;
    private Metadata metadata;
    private String status;
}