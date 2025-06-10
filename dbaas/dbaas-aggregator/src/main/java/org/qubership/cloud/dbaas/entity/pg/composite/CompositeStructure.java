package org.qubership.cloud.dbaas.entity.pg.composite;

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
