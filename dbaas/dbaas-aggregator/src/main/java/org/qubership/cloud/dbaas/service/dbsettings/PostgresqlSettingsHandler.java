package org.qubership.cloud.dbaas.service.dbsettings;

import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.exceptions.InvalidDbSettingsException;
import jakarta.validation.constraints.NotEmpty;

import org.apache.commons.collections4.MapUtils;

import java.util.*;

public class PostgresqlSettingsHandler implements LogicalDbSettingsHandler {

    static final String PG_EXTENSIONS = "pgExtensions";
    static final String DROP_EXTENSIONS = "dropExtensions";
    private final DefaultDbSettingsHandler defaultDbSettings;

    public PostgresqlSettingsHandler(DefaultDbSettingsHandler defaultDbSettings) {
        this.defaultDbSettings = defaultDbSettings;
    }

    @Override
    public String dbType() {
        return "postgresql";
    }

    @Override
    public String updateSettings(DatabaseRegistry databaseRegistry, Map<String, Object> newSettings) {
        Map<String, Object> currentSettings = databaseRegistry.getSettings();

        if (needToHandlePgExtensions(currentSettings, newSettings) && validPgExtensionRequest(newSettings)) {
            updatePgExtensions(currentSettings, newSettings);
        }

        return defaultDbSettings.updateSettings(databaseRegistry, newSettings);
    }

    private boolean needToHandlePgExtensions(Map<String, Object> currentSettings, Map<String, Object> newSettings) {
        return !MapUtils.isEmpty(newSettings) &&
                validPgExtensionRequest(newSettings) &&
                isPgExtensionsDifferent(currentSettings, newSettings);
    }

    void updatePgExtensions(Map<String, Object> currentSettings, @NotEmpty Map<String, Object> newSettings) {
        List<String> pgExtensions = new ArrayList<>();

        addExtensions(currentSettings, pgExtensions);
        addExtensions(newSettings, pgExtensions);
        removeExtensions(newSettings, pgExtensions);

        newSettings.put(PG_EXTENSIONS, pgExtensions);
        newSettings.remove(DROP_EXTENSIONS);
    }

    @SuppressWarnings("unchecked")
    private boolean validPgExtensionRequest(Map<String, Object> newSettings) {
        Collection<String> pgExtensions = (Collection<String>) newSettings.get(PG_EXTENSIONS);
        Collection<String> dropExtensions = (Collection<String>) newSettings.get(DROP_EXTENSIONS);
        if (pgExtensions != null && dropExtensions != null && haveCommonElements(pgExtensions, dropExtensions)) {
            throw new InvalidDbSettingsException(String.format("'%s' and '%s' parameters contain common extensions. " +
                    "You must specify whether it is needed to remove or add these extensions. Settings in request: %s", PG_EXTENSIONS, DROP_EXTENSIONS, newSettings));
        }
        return true;
    }

    private static boolean haveCommonElements(Collection<String> pgExtensions, Collection<String> dropExtensions) {
        return dropExtensions.stream().anyMatch(pgExtensions::contains);
    }

    @SuppressWarnings("unchecked")
    private void addExtensions(Map<String, Object> settings, List<String> pgExtensions) {
        if (settings != null && settings.containsKey(PG_EXTENSIONS)) {
            Collection<String> extensions = (Collection<String>) settings.get(PG_EXTENSIONS);
            if (extensions != null) {
                addUniqExtensions(extensions, pgExtensions);
            }
        }
    }

    private void addUniqExtensions(Collection<String> extensions, List<String> pgExtensions) {
        for (String extension : extensions) {
            if (!pgExtensions.contains(extension)) {
                pgExtensions.add(extension);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void removeExtensions(Map<String, Object> settings, List<String> pgExtensions) {
        if (settings != null && settings.containsKey(DROP_EXTENSIONS)) {
            Collection<String> extensions = (Collection<String>) settings.get(DROP_EXTENSIONS);
            if (extensions != null) {
                pgExtensions.removeAll(extensions);
            }
        }
    }

    private boolean isPgExtensionsDifferent(Map<String, Object> currentSettings, Map<String, Object> newSettings) {
        List<String> currentPgExtensions = getExtensions(currentSettings);
        List<String> newPgExtensions = getExtensions(newSettings);
        return !Objects.equals(currentPgExtensions, newPgExtensions);
    }

    @SuppressWarnings("unchecked")
    private List<String> getExtensions(Map<String, Object> settings) {
        List<String> pgExtensions = new ArrayList<>();
        if (settings != null && settings.containsKey(PG_EXTENSIONS)) {
            Collection<String> extensions = (Collection<String>) settings.get(PG_EXTENSIONS);
            if (extensions != null) {
                pgExtensions.addAll(extensions);
            }
        }
        return pgExtensions;
    }
}
