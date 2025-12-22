package com.netcracker.it.dbaas.entity;
import lombok.*;

import jakarta.annotation.Nonnull;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class Metadata {
    @Nonnull
    private String apiVersion;

    @Nonnull
    private List<String> supportedRoles;

    @Nonnull
    private Map<String, Boolean> features;
}