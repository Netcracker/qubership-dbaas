package com.netcracker.it.dbaas.entity.backup.v3;

import lombok.Data;

import jakarta.validation.constraints.NotNull;

@Data
public class DeleteResult {

    @NotNull
    private Status status = Status.PROCEEDING;

}
