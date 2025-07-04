package org.qubership.cloud.dbaas.entity.pg.composite;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@Table(name = "composite_namespace")
@Entity(name = "CompositeNamespace")
public class CompositeNamespace {
    @Id
    private UUID id;
    private String baseline;
    private String namespace;

    public CompositeNamespace(String baseline, String namespace) {
        this.baseline = baseline;
        this.namespace = namespace;
    }
}