package com.netcracker.it.dbaas.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@ToString
public class PasswordChangeResponse {
    private List<PasswordChanged> changed = new ArrayList<>();
    private List<PasswordFailed> failed = new ArrayList<>();
    private int failedHttpStatus = 0;

    public void putSuccessEntity(Map<String, Object> classifier, Map<String, Object> connection) {
        changed.add(new PasswordChanged(classifier, connection));
    }

    public void putFailedEntity(Map<String, Object> classifier, String message) {
        failed.add(new PasswordFailed(classifier, message));
    }

    public void setFailedHttpStatus(int failedHttpStatus) {
        this.failedHttpStatus = failedHttpStatus;
    }
}




