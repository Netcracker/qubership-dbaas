package org.qubership.cloud.encryption.config.keystore.type;

import java.util.List;

public interface KeystoreConfigBuilder<T extends KeystoreConfigBuilder<T>> {
    T setKeys(List<KeyConfig> keys);
}

