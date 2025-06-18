package org.qubership.cloud.encryption.config;

import java.io.File;
import java.io.OutputStream;

/**
 * Abstraction of configuration serializing service.
 */
public interface ConfigurationSerializer {
    /**
     * Save configuration to file.
     * @param file configuration file
     * @param config configuration instance
     */
    void saveConfiguration(File file, EncryptionConfiguration config);
    
    /**
     * Save configuration to stream.
     * @param outputStream stream to store configuration
     * @param config configuration instance
     */
    void saveConfiguration(OutputStream outputStream, EncryptionConfiguration config);
}
