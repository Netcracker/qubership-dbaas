package com.netcracker.cloud.encryption.config.xml.pojo.keystore;

public interface KeystoreConfigFactory {
    KeyStoreSubsystemXmlConf createSubsystemType();

    RemoteKeystoreXmlConf createRemoteKeystoreType();

    LocalKeystoreXmlConf createLocalKeystoreType();
}
