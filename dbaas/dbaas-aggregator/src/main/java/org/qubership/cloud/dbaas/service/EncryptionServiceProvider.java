package org.qubership.cloud.dbaas.service;

import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.List;

@Slf4j
public class EncryptionServiceProvider {

    private List<DataEncryption> availableEncryptionServices;

    public EncryptionServiceProvider(List<DataEncryption> availableEncryptionServices) {
        this.availableEncryptionServices = availableEncryptionServices;
    }

    public DataEncryption getEncryptionService() {
        return availableEncryptionServices.stream()
                .sorted(Comparator.comparingInt(DataEncryption::getOrder))
                .findFirst().get();
    }

    public DataEncryption getEncryptionService(String encryptedData) {
        return availableEncryptionServices.stream()
                .filter(service -> service.isKnowEncryptedFormat(encryptedData))
                .findFirst().get();
    }

}
