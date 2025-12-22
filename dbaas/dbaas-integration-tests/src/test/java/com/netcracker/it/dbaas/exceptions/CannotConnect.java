package com.netcracker.it.dbaas.exceptions;

public class CannotConnect extends AssertionError {
    public CannotConnect(Throwable error){
        super(error);
    }
}
