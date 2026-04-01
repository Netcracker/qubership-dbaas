package com.netcracker.it.dbaas.entity;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;
import lombok.Data;
import lombok.ToString;

import java.util.List;
import java.util.UUID;

@Data
@JsonIdentityInfo(
        generator = ObjectIdGenerators.PropertyGenerator.class,
        property = "id")
public class BgDomain {

    private UUID id;

    @ToString.Exclude
    private List<BgNamespace> namespaces;

    private String controllerNamespace;

}
