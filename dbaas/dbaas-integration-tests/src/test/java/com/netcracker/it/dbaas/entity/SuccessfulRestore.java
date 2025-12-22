package com.netcracker.it.dbaas.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SuccessfulRestore {
    private Map<String, Object> connectionProperties;
}
