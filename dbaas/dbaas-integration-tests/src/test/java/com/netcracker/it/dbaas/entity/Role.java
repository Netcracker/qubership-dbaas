package com.netcracker.it.dbaas.entity;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum Role {
    ADMIN("admin"),
    RW("rw");

    private final String roleValue;

    public String getRoleValue() {
        return roleValue;
    }

    Role(String roleValue) {
        this.roleValue = roleValue;
    }

    @Override
    public String toString() {
        return roleValue;
    }

    @JsonCreator
    public static Role fromString(String text){
        for(Role r : Role.values()){
            if(r.getRoleValue().equalsIgnoreCase(text)){
                return r;
            }
        }
        throw new IllegalArgumentException();
    }
}
