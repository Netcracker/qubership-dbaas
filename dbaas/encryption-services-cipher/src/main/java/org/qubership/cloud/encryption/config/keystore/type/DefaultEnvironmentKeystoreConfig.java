package org.qubership.cloud.encryption.config.keystore.type;

import com.google.common.base.MoreObjects;

import java.util.Collections;
import java.util.List;

public class DefaultEnvironmentKeystoreConfig implements EnvironmentKeystoreConfig {
    private static final String DEFAULT_SYMM_KEY_VARNAME = "SYM_KEY";// TODO need a more friendly name for the symmetric
                                                                     // key
    private static final String DEFAULT_ENV_KEY_VARNAME = "DEFAULT_KEY"; // TODO need a more friendly name for the
                                                                         // default key
    private static final String DEFAULT_ENV_KEY_PREFIX = "KS_"; // TODO need a more friendly prefix for the keys

    @Override
    public String getKeystoreIdentifier() {
        return "DefaultEnvKeystore";
    }

    @Override
    public boolean isDeprecated() {
        return false;
    }

    @Override
    public List<KeyConfig> getKeys() {
        return Collections.emptyList();
    }

    @Override
    public String getPrefix() {
        return DEFAULT_ENV_KEY_PREFIX;
    }

    @Override
    public boolean isEncrypted() {
        final String symmetricKey = System.getenv(DEFAULT_SYMM_KEY_VARNAME);
        return symmetricKey != null && !symmetricKey.trim().isEmpty();
    }

    @Override
    public String getPasswordVar() {
        return DEFAULT_SYMM_KEY_VARNAME;
    }

    @Override
    public String getDefaultKeyVar() {
        return DEFAULT_ENV_KEY_VARNAME;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("identifier", getKeystoreIdentifier()).add("prefix", getPrefix())
                .add("encrypted", isEncrypted()).add("passwordVar", getPasswordVar()).toString();
    }
}
