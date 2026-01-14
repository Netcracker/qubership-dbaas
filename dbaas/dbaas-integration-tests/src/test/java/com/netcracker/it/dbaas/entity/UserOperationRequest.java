package com.netcracker.it.dbaas.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.util.SortedMap;

@Data
@AllArgsConstructor
@RequiredArgsConstructor
public class UserOperationRequest {
    private SortedMap<String, Object> classifier;

    private String logicalUserId;

    private String type;

    private  String userId;
}
