package org.qubership.cloud.encryption.config;

import java.io.File;
import java.io.InputStream;

/**
 * Abstraction of configuration deserializing service.
 */
public interface ConfigurationParser {
    /**
     * Load configuration from file. 
     * @param file configuration file 
     * @return configuration instance
     */
    EncryptionConfiguration loadConfiguration(File file);

    /**
     * Load configuration from stream.
     * @param inputStream stream-source of configuration 
     * @return configuration instance
     */
    EncryptionConfiguration loadConfiguration(InputStream inputStream);
}
