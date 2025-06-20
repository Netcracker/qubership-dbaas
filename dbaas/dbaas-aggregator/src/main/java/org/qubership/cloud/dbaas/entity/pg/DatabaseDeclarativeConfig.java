package org.qubership.cloud.dbaas.entity.pg;

import org.qubership.cloud.dbaas.converter.MapConverter;
import org.qubership.cloud.dbaas.dto.declarative.DatabaseDeclaration;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.util.*;

import static org.qubership.cloud.dbaas.Constants.*;

@Data
@Entity
@Table(name = "database_declarative_config")
public class DatabaseDeclarativeConfig implements Serializable {

    @Id
    @GeneratedValue
    private UUID id;

    private Boolean lazy;

    @Convert(converter = MapConverter.class)
    private Map<String, Object> settings;

    @Column(name = "instantiation_approach")
    private String instantiationApproach;

    @Column(name = "versioning_approach")
    private String versioningApproach;

    @Column(name = "versioning_type")
    private String versioningType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private SortedMap<String, Object> classifier;

    private String type;

    @Column(name = "name_prefix")
    private String namePrefix;

    private String namespace;


    public DatabaseDeclarativeConfig() {
    }

    public DatabaseDeclarativeConfig(DatabaseDeclarativeConfig config) {
        this.settings = config.getSettings() == null ? null : new HashMap<>(config.getSettings());
        this.lazy = config.getLazy();
        this.instantiationApproach = config.getInstantiationApproach();
        this.versioningApproach = config.getVersioningApproach();
        this.versioningType = config.getVersioningType();
        this.classifier = new TreeMap<>(config.getClassifier());
        this.type = config.getType();
        this.namePrefix = config.getNamePrefix();
        this.namespace = config.getNamespace();
    }

    public DatabaseDeclarativeConfig(DatabaseDeclaration declaration, SortedMap<String, Object> classifier, String namespace) {
        this.settings = declaration.getSettings() == null ? null : new HashMap<>(declaration.getSettings());
        this.namePrefix = declaration.getNamePrefix();
        this.lazy = declaration.getLazy();
        this.versioningType = declaration.getVersioningConfig() == null ? STATIC_STATE : VERSION_STATE;
        this.instantiationApproach = declaration.getInitialInstantiation() == null ? NEW_MODE : declaration.getInitialInstantiation().getApproach();
        this.versioningApproach = declaration.getVersioningConfig() == null ? NEW_MODE : declaration.getVersioningConfig().getApproach();
        this.classifier = new TreeMap<>(classifier);
        this.type = declaration.getType();
        this.namespace = namespace;
    }

    public boolean isDatabaseRegistryBaseOnConfig(DatabaseRegistry dbr) {
        if (!this.type.equals(dbr.getType())) {
            return false;
        }
        TreeMap<String, Object> classifier = new TreeMap<>(dbr.getClassifier());
        if (SCOPE_VALUE_TENANT.equals(classifier.get(SCOPE))) {
            classifier.remove(TENANT_ID);
        }
        return this.classifier.equals(classifier);
    }
}
