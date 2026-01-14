package com.netcracker.it.dbaas.entity;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;
import lombok.Data;

import java.util.Date;

@Data
@JsonIdentityInfo(
        generator = ObjectIdGenerators.PropertyGenerator.class,
        property = "namespace")
public class BgNamespace {
    private String namespace;
    private String state;
    private String version;
    private Date updateTime;
    private BgDomain bgDomain;
}
