package com.netcracker.cloud.dbaas.utils;

import jakarta.ws.rs.core.Response;

public enum CustomExceptionStatus implements Response.StatusType {

    UNPROCESSABLE_ENTITY(422, Response.Status.Family.CLIENT_ERROR, "Unprocessable Entity");

    private final int code;
    private final Response.Status.Family family;
    private final String reason;

    CustomExceptionStatus(int code, Response.Status.Family family, String reason) {
        this.code = code;
        this.family = family;
        this.reason = reason;
    }

    @Override
    public int getStatusCode() {
        return this.code;
    }

    @Override
    public Response.Status.Family getFamily() {
        return this.family;
    }

    @Override
    public String getReasonPhrase() {
        return this.reason;
    }

    public static CustomExceptionStatus fromStatusCode(int code) {
        for (CustomExceptionStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        return null;
    }
}
