package org.qubership.cloud.encryption.config.xml.matchers;

import org.qubership.cloud.encryption.config.crypto.CryptoSubsystemConfig;
import org.hamcrest.FeatureMatcher;
import org.hamcrest.Matcher;

import javax.annotation.Nonnull;

public final class CryptoConfigurationMatchers {
    private CryptoConfigurationMatchers() {}

    @Nonnull
    public static <T extends CryptoSubsystemConfig> Matcher<T> defaultAlgorithm(
            final Matcher<? super String> algorithmMatcher) {
        return new FeatureMatcher<T, String>(algorithmMatcher, "Default algorithm - ", "algorithm -") {
            @Override
            protected String featureValueOf(final T config) {
                return config.getDefaultAlgorithm().orNull();
            }
        };
    }

    @Nonnull
    public static <T extends CryptoSubsystemConfig> Matcher<T> defaultKeyAlias(
            final Matcher<? super String> keyAliasMatcher) {
        return new FeatureMatcher<T, String>(keyAliasMatcher, "Default key alias - ", "key alias -") {
            @Override
            protected String featureValueOf(final T config) {
                return config.getDefaultKeyAlias().orNull();
            }
        };
    }

    @Nonnull
    public static <T extends CryptoSubsystemConfig> Matcher<T> keyStoreName(
            final Matcher<? super String> keyStoreNameMatcher) {
        return new FeatureMatcher<T, String>(keyStoreNameMatcher, "Keystore name - ", "name -") {
            @Override
            protected String featureValueOf(final T config) {
                return config.getKeyStoreName().orNull();
            }
        };
    }
}
