package com.netcracker.cloud.dbaas.entity.pg.backupV2;

import com.netcracker.cloud.dbaas.enums.ClassifierType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Objects;
import java.util.SortedMap;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClassifierDetails {
    private ClassifierType type;
    private String previousDatabase;
    private SortedMap<String, Object> classifier;
    private SortedMap<String, Object> classifierBeforeMapper;

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ClassifierDetails that)) return false;
        return type == that.type && Objects.equals(classifier, that.classifier) && Objects.equals(classifierBeforeMapper, that.classifierBeforeMapper);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, classifier, classifierBeforeMapper);
    }
}
