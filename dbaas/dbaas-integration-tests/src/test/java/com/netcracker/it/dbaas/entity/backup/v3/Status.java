package com.netcracker.it.dbaas.entity.backup.v3;

public enum Status {
    SUCCESS, PROCEEDING, FAIL //do not change the order, max by ordinal used to aggregate
    //just one fail means namespace restore failed, just one proceeding (without fails) means namespace restore proceeding
}
