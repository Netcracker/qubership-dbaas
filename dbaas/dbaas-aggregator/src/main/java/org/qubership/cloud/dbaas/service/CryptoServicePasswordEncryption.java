package org.qubership.cloud.dbaas.service;

import org.qubership.cloud.dbaas.dto.Secret;
import org.qubership.cloud.encryption.cipher.CryptoService;
import org.qubership.cloud.encryption.cipher.CryptoServiceImpl;
import org.qubership.cloud.encryption.cipher.provider.CryptoProvider;
import org.qubership.cloud.encryption.cipher.provider.V2CryptoProvider;
import org.qubership.cloud.encryption.config.keystore.type.DefaultEnvironmentKeystoreConfig;
import org.qubership.cloud.encryption.config.xml.pojo.crypto.CryptoSubsystemXmlConf;
import org.qubership.cloud.encryption.config.xml.pojo.keystore.KeyStoreSubsystemXmlConf;
import org.qubership.cloud.encryption.key.EnvironmentKeyStore;
import org.qubership.cloud.encryption.key.KeyStoreRepository;
import org.qubership.cloud.encryption.key.KeyStoreRepositoryImpl;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.regex.Pattern;

@Slf4j
public class CryptoServicePasswordEncryption implements DataEncryption {

    /**
     * Regexp to check that the data has been encrypted by V2CryptoProvider
     *
     * @see org.qubership.cloud.dbaas.service.encryption.cipher.provider.V2CryptoProvider
     */
    private static final Pattern TEMPLATE_REGEXP = Pattern.compile(
            "^\\{v2c\\}(\\{(?<algorithm>[^\\{\\}]*)\\}){1}(\\{(?<keyAlias>[^\\{\\}]*)\\}){1}(\\{(?<iv>[a-zA-Z0-9\\=\\/\\+]+)\\}){0,1}(\\{(?<cryptData>[a-zA-Z0-9\\=\\/\\+]+)\\}){1}$");

    private CryptoService cryptoService;

    public CryptoServicePasswordEncryption(String keyPassword, String key) {
        cryptoService = createCryptoService();

    }

    @Override
    public String encrypt(Secret secret) {
        return cryptoService.encrypt(secret.getData());
    }

    @Override
    public Secret decrypt(String data) {
        Secret secret = new Secret();
        secret.setData(cryptoService.decrypt(data).getResultAsString());
        return secret;
    }

    @Override
    public boolean isKnowEncryptedFormat(String data) {
        return TEMPLATE_REGEXP.matcher(data).find();
    }

    @Override
    public void remove(String data) {
    }

    @Override
    public int getOrder() {
        return 1;
    }

    private CryptoService createCryptoService() {
        DefaultEnvironmentKeystoreConfig environmentKeystoreConfig = new DefaultEnvironmentKeystoreConfig();
        EnvironmentKeyStore environmentKeyStore = new EnvironmentKeyStore(environmentKeystoreConfig);

        CryptoSubsystemXmlConf cryptoSubsystemConfig = new CryptoSubsystemXmlConf();
        cryptoSubsystemConfig.setDefaultAlgorithm("AES");
        cryptoSubsystemConfig.setDefaultKeyAlias("DEFAULT_KEY");
        cryptoSubsystemConfig.setKeyStoreName(environmentKeystoreConfig.getKeystoreIdentifier());

        KeyStoreSubsystemXmlConf keystoreSubsystemConfig = new KeyStoreSubsystemXmlConf();
        DefaultEnvironmentKeystoreConfig keystoreConfig = new DefaultEnvironmentKeystoreConfig();
        keystoreSubsystemConfig.setKeyStores(List.of(keystoreConfig));
        keystoreSubsystemConfig.setDefaultKeyStore(keystoreConfig);
        KeyStoreRepository keyStoreRepository = new KeyStoreRepositoryImpl(keystoreSubsystemConfig);

        CryptoProvider cryptoProvider = new V2CryptoProvider(environmentKeyStore, keyStoreRepository, cryptoSubsystemConfig);
        return new CryptoServiceImpl(cryptoProvider, List.of(cryptoProvider));
    }
}