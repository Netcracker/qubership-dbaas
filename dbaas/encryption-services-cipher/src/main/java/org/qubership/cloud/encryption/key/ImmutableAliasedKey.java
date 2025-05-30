package org.qubership.cloud.encryption.key;

import com.google.common.base.MoreObjects;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.security.Key;

public class ImmutableAliasedKey implements AliasedKey {
    private Optional<String> alias;
    private Key key;
    private boolean deprecated;

    public ImmutableAliasedKey(@Nonnull Key key) {
        this(key, null);
    }

    public ImmutableAliasedKey(@Nonnull Key key, @Nullable String alias) {
        this(key, alias, false);
    }

    public ImmutableAliasedKey(@Nonnull Key key, @Nullable String alias, boolean deprecated) {
        this.alias = Optional.fromNullable(alias);
        this.key = Preconditions.checkNotNull(key, "Key can't be null");
        this.deprecated = deprecated;
    }

    @Nonnull
    @Override
    public Optional<String> getAlias() {
        return alias;
    }

    @Nonnull
    @Override
    public Key getKey() {
        return key;
    }

    @Override
    public boolean isDeprecated() {
        return deprecated;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("alias", alias).add("key", key).add("deprecated", deprecated)
                .toString();
    }
}

