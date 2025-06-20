package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.dbaas.entity.pg.backup.NamespaceRestoration;
import lombok.Getter;

public class NamespaceRestorationFailedException extends Exception {
    @Getter
    private NamespaceRestoration restoration;

    public NamespaceRestorationFailedException(String message, Throwable cause, NamespaceRestoration restoration) {
        super("Failed to restore " + restoration.getId() + " because of " + (cause != null ? cause.getMessage() : "") + " : " + message, cause);
        this.restoration = restoration;
    }

}
