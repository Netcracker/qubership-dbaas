package com.netcracker.cloud.dbaas.entity.pg.composite;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

import lombok.Data;
import lombok.NonNull;

import java.util.Set;

@Data
public class CompositeStructure {
    @NonNull
    private String baseline;
    @NonNull
    private Set<String> namespaces;
}
