package com.netcracker.it.dbaas.entity;

import lombok.Data;
import lombok.NonNull;

@Data
public class DbResource {
    public final static String USER_KIND = "user";
    public final static String DATABASE_KIND = "database";

    @NonNull
    private String kind;
    @NonNull
    private String name;
}
