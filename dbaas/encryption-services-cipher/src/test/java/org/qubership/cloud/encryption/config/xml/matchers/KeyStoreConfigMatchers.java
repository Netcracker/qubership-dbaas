package org.qubership.cloud.encryption.config.xml.matchers;

import org.qubership.cloud.encryption.config.keystore.type.KeyConfig;
import org.qubership.cloud.encryption.config.keystore.type.KeystoreConfig;
import org.qubership.cloud.encryption.config.keystore.type.LocalKeystoreConfig;
import org.hamcrest.FeatureMatcher;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;

import javax.annotation.Nonnull;
import java.util.List;

public final class KeyStoreConfigMatchers {
    private KeyStoreConfigMatchers() {}

    @Nonnull
    public static <T extends KeystoreConfig> Matcher<T> keyStoreIdentity(final Matcher<String> nameMatcher) {
        return new FeatureMatcher<T, String>(nameMatcher, "Keystore configuration identity name - ",
                "identity name -") {
            @Override
            protected String featureValueOf(final T config) {
                return config.getKeystoreIdentifier();
            }
        };
    }

    @Nonnull
    public static <T extends LocalKeystoreConfig> Matcher<T> keyStoreLocation(final Matcher<String> pathMatcher) {
        return new FeatureMatcher<T, String>(pathMatcher, "Keystore file location - ", "location -") {
            @Override
            protected String featureValueOf(final T config) {
                return config.getLocation();
            }
        };
    }

    @Nonnull
    public static <T extends LocalKeystoreConfig> Matcher<T> keyStoreType(final Matcher<String> typeMatcher) {
        return new FeatureMatcher<T, String>(typeMatcher, "Keystore type - ", "type -") {
            @Override
            protected String featureValueOf(final T config) {
                return config.getKeystoreType();
            }
        };
    }

    @Nonnull
    public static <T extends LocalKeystoreConfig> Matcher<T> keyStorePassword(
            final Matcher<? super String> typeMatcher) {
        return new FeatureMatcher<T, String>(typeMatcher, "Keystore password - ", "password -") {
            @Override
            protected String featureValueOf(final T config) {
                return config.getPassword();
            }
        };
    }

    @Nonnull
    public static <T extends LocalKeystoreConfig> Matcher<T> keyStoreIsDeprecated(
            final Matcher<? super Boolean> typeMatcher) {
        return new FeatureMatcher<T, Boolean>(typeMatcher, "Keystore deprecated - ", "deprecated -") {
            @Override
            protected Boolean featureValueOf(final T config) {
                return config.isDeprecated();
            }
        };
    }

    @Nonnull
    public static <T extends LocalKeystoreConfig> Matcher<T> emptyKeys() {
        return new FeatureMatcher<T, List<KeyConfig>>(Matchers.emptyIterable(), "Keystore deprecated - ",
                "deprecated -") {
            @Override
            protected List<KeyConfig> featureValueOf(final T config) {
                return config.getKeys();
            }
        };
    }
}

