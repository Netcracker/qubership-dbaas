package org.qubership.cloud.encryption.config.xml.matchers;

import org.qubership.cloud.encryption.config.keystore.KeystoreSubsystemConfig;
import org.qubership.cloud.encryption.config.keystore.type.KeystoreConfig;
import org.hamcrest.FeatureMatcher;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;

import javax.annotation.Nonnull;
import java.util.List;

public final class KeyStoreSubsystemConfigMatchers {
    private KeyStoreSubsystemConfigMatchers() {}

    @Nonnull
    public static <T extends KeystoreSubsystemConfig> Matcher<T> defaultKeyStoreConfig(
            final Matcher<? super KeystoreConfig> configMatcher) {
        return new FeatureMatcher<T, KeystoreConfig>(configMatcher, "Default keystore configuration - ",
                "keystore configuration -") {
            @Override
            protected KeystoreConfig featureValueOf(final T config) {
                return config.getDefaultKeyStore();
            }
        };
    }

    public static <T extends KeystoreSubsystemConfig> Matcher<T> emptyListKeyStoreConfigs() {
        return new FeatureMatcher<T, List<KeystoreConfig>>(Matchers.emptyIterable(),
                "Keystore subsystem not contains empty configure keystore config list - ", "keystore config list -") {
            @Override
            protected List<KeystoreConfig> featureValueOf(final T config) {
                return config.getKeyStores();
            }
        };
    }
}
