package com.netcracker.it.dbaas.entity.config;

import com.netcracker.it.dbaas.helpers.ClassifierBuilder;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
public class DatabaseDeclaration implements DeclarativeConfig {

    private ClassifierConfig classifierConfig;

    private Boolean lazy = false;

    private String type;

    private Map<String, Object> settings;

    private String namePrefix;

    private VersioningConfig versioningConfig;

    private InitialInstantiation initialInstantiation;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ClassifierConfig {
        private Map<String, Object> classifier;
    }

    @Data
    public static class VersioningConfig {
        protected String approach = "clone";

    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class InitialInstantiation {
        private String approach = "new";

        private Map<String, Object> sourceClassifier;
    }

    @Override
    public DeclarativePayload asPayload(String namespace, String serviceName) {
        return new DeclarativePayload(serviceName, namespace, serviceName + "-databaseDeclaration", "DatabaseDeclaration", this);
    }

    public static class DeclarativeDBConfigBuilder {
        private final DatabaseDeclaration databaseDeclaration;

        public DeclarativeDBConfigBuilder() {
            this.databaseDeclaration = new DatabaseDeclaration();
            type("postgresql");
            initNew();
        }

        public DeclarativeDBConfigBuilder type(String type) {
            databaseDeclaration.setType(type);
            return this;
        }

        public DeclarativeDBConfigBuilder initNew() {
            databaseDeclaration.setInitialInstantiation(new DatabaseDeclaration.InitialInstantiation());
            return this;
        }

        public DeclarativeDBConfigBuilder initClone(Map<String, Object> sourceClassifier) {
            databaseDeclaration.setInitialInstantiation(new DatabaseDeclaration.InitialInstantiation("clone", sourceClassifier));
            return this;
        }

        public DeclarativeDBConfigBuilder initClone(ClassifierBuilder sourceClassifierBuilder) {
            databaseDeclaration.setInitialInstantiation(
                    new DatabaseDeclaration.InitialInstantiation("clone", sourceClassifierBuilder.declarative().build()));
            return this;
        }

        public DeclarativeDBConfigBuilder classifier(Map<String, Object> declarativeClassifier) {
            databaseDeclaration.setClassifierConfig(new DatabaseDeclaration.ClassifierConfig(declarativeClassifier));
            return this;
        }

        public DeclarativeDBConfigBuilder classifier(ClassifierBuilder classifierBuilder) {
            return this.classifier(classifierBuilder.declarative().build());
        }

        public DeclarativeDBConfigBuilder versioning(String approach) {
            DatabaseDeclaration.VersioningConfig versioningConfig = new DatabaseDeclaration.VersioningConfig();
            versioningConfig.setApproach(approach);
            databaseDeclaration.setVersioningConfig(versioningConfig);
            return this;
        }

        public DeclarativeDBConfigBuilder lazy() {
            databaseDeclaration.setLazy(true);
            return this;
        }

        public DatabaseDeclaration build() {
            return databaseDeclaration;
        }
    }
}
