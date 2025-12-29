package com.netcracker.it.dbaas.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public class UpdateClassifierRequestV3 extends UpdateClassifierRequest {
    boolean clone = false;
}
