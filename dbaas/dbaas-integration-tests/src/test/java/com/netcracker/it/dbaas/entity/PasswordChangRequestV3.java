package com.netcracker.it.dbaas.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public class PasswordChangRequestV3 extends PasswordChangeRequest{
    private String originService;
    private String userRole;
}
