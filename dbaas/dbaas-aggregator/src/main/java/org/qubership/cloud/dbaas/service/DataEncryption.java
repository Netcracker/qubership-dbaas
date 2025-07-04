package org.qubership.cloud.dbaas.service;

import org.qubership.cloud.dbaas.dto.Secret;

public interface DataEncryption {

    String encrypt(Secret data);
    Secret decrypt(String data);
    void remove(String data);
    boolean isKnowEncryptedFormat(String data);
    int getOrder();
}
