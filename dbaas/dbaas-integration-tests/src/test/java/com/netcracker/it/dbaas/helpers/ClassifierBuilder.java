package com.netcracker.it.dbaas.helpers;

import java.util.HashMap;
import java.util.Map;

import static com.netcracker.it.dbaas.helpers.DbaasHelperV3.*;

public class ClassifierBuilder {
    private final Map<String, Object> classifier;

    public ClassifierBuilder() {
        this.classifier = new HashMap<>();
        this.classifier.put("microserviceName", TEST_MICROSERVICE_NAME);
        this.classifier.put("scope", "service");
        this.classifier.put("namespace", TEST_NAMESPACE);
    }

    public ClassifierBuilder(Map<String, Object> classifier) {
        this.classifier = classifier;
    }

    public ClassifierBuilder ms(String microserviceName) {
        this.classifier.put("microserviceName", microserviceName);
        return this;
    }

    public ClassifierBuilder ns(String namespace) {
        this.classifier.put("namespace", namespace);
        return this;
    }

    public ClassifierBuilder scope(String scope) {
        this.classifier.put("scope", scope);
        return this;
    }

    public ClassifierBuilder tenant() {
        this.classifier.put("scope", "tenant");
        return this;
    }

    public ClassifierBuilder test(String testValue) {
        this.classifier.put(TEST_CLASSIFIER_KEY, testValue);
        return this;
    }

    public ClassifierBuilder declarative() {
        this.classifier.remove("namespace");
        return this;
    }

    public ClassifierBuilder customKeys(String key, String value) {
        ((HashMap<String, Object>) this.classifier.computeIfAbsent("custom_keys", k -> new HashMap<String, Object>())).put(key, value);
        return this;
    }

    public Map<String, Object> build() {
        return this.classifier;
    }
}
