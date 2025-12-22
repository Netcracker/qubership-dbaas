package com.netcracker.it.dbaas.entity.composite;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

@Data
@NoArgsConstructor
public class CompositeStructureDto {

    private String id;
    private Set<String> namespaces;


    public CompositeStructureDto(String id, Set<String> namespaces) {
        this.id = id;
        this.namespaces = namespaces;
    }
}