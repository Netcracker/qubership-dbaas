package org.qubership.cloud.dbaas.aspect;

import org.qubership.cloud.dbaas.monitoring.annotation.TimeMeasure;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class TestAspectService implements TestAspectInterface {

    String testStringField = "testStringValue";
    private Integer testIntegerField = 1;
    private int testIntField = 1;
    private double testDoubleField = 1.01;

    @TimeMeasure(value = "some.metric")
    @Override
    public void firstTestMethod() {
    }

    @TimeMeasure
    @Override
    public void secondTestMethod() {
    }

    @TimeMeasure(value = "some.metric", tags = {"some-tag1", "some-value1"})
    @Override
    public void thirdTestMethod() {
    }

    @TimeMeasure(value = "some.metric", fieldTags = {"testStringField", "testIntegerField", "testIntField", "testDoubleField"})
    @Override
    public void forthTestMethod() {
    }

    @TimeMeasure(value = "some.metric",
            tags = {"some-tag1", "some-value1"},
            fieldTags = {"testStringField", "testIntegerField", "testIntField", "testDoubleField"})
    @Override
    public void fifthTestMethod() {
    }
}