package com.netcracker.cloud.encryption.config.keystore.type;

import com.google.common.base.MoreObjects;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

public class DefaultEnvironmentKeystoreConfig implements EnvironmentKeystoreConfig {
    private static final String DEFAULT_SYMM_KEY_VARNAME = "SYM_KEY";// TODO need a more friendly name for the symmetric
                                                                     // key
    private static final String DEFAULT_ENV_KEY_VARNAME = "DEFAULT_KEY"; // TODO need a more friendly name for the
                                                                         // default key
    private static final String DEFAULT_ENV_KEY_PREFIX = "KS_"; // TODO need a more friendly prefix for the keys
    private static final String POD_SECRETS_PATH = "/etc/secrets/pod-secrets";

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
        if (symmetricKey != null && !symmetricKey.trim().isEmpty()) {
            return true;
        }
        Path secretFile = Paths.get(POD_SECRETS_PATH, DEFAULT_SYMM_KEY_VARNAME);
        if (Files.exists(secretFile)) {
            try {
                return !Files.readString(secretFile).isBlank();
            } catch (IOException e) {
                return false;
            }
        }
        return false;
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
