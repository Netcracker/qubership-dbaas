package com.netcracker.cloud.dbaas.entity.pg.composite;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@Table(name = "composite_properties")
@Entity(name = "CompositeProperties")
public class CompositeProperties {
    @Id
    @Column(name = "composite_namespace_id")
    public UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "composite_namespace_id")
    private CompositeNamespace compositeNamespace;

    @Column(name = "modify_index", nullable = false)
    private long modifyIndex;

    public CompositeProperties(CompositeNamespace compositeNamespace, long modifyIndex) {
        this.compositeNamespace = compositeNamespace;
        this.modifyIndex = modifyIndex;
    }
}
