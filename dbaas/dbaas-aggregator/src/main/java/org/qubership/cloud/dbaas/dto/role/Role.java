package org.qubership.cloud.dbaas.dto.role;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum Role {
    ADMIN("admin");

    private final String roleValue;

    @Override
    public String toString() {
        return roleValue;
    }

    Role(String roleValue) {
        this.roleValue = roleValue;
    }


    @JsonCreator
    public static Role fromString(String text){
        for(Role r : Role.values()){
            if(r.toString().equalsIgnoreCase(text)){
                return r;
            }
        }
        throw new IllegalArgumentException("cant find role with value=" + text);
    }
}
