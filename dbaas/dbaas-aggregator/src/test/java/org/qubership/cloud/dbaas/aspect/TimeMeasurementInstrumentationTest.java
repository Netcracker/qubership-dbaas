package org.qubership.cloud.dbaas.aspect;

import org.qubership.cloud.dbaas.integration.config.PostgresqlContainerResource;
import org.qubership.cloud.dbaas.integration.profiles.DirtiesMetricsProfile;
import org.qubership.cloud.dbaas.monitoring.interceptor.TimeMeasurementManager;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
@Slf4j
@TestProfile(DirtiesMetricsProfile.class)
public class TimeMeasurementInstrumentationTest {

    @Inject
    public MeterRegistry meterRegistry;

    @Inject
    private TestAspectService timeMeasure;
    @Inject
    private TimeMeasurementManager timeMeasurementManager;

    @AfterEach
    void afterEach() {
        meterRegistry.clear();
    }

    @Test
    void testProxyTimeMeasurement() {
        TestAspectInterface proxy = (TestAspectInterface) Proxy.newProxyInstance(TestAspectInterface.class.getClassLoader(), new Class[]{TestAspectInterface.class},
                timeMeasurementManager.provideTimeMeasurementInvocationHandler(new TestAspectService()));
        proxy.firstTestMethod();
        assertRegistryTimeMeasurement();
    }

    @Test
    void testBeanTimeMeasurement() {
        timeMeasure.firstTestMethod();
        assertRegistryTimeMeasurement();
    }

    private void assertRegistryTimeMeasurement() {
        List<Meter> meters = meterRegistry.getMeters().stream().collect(Collectors.toList());
        Assertions.assertTrue(meters.stream().anyMatch(meter -> "some.metric".equals(meter.getId().getName())
                && Collections.emptyList().equals(meter.getId().getTags())));
        Assertions.assertTrue(meters.stream().anyMatch(meter -> "some.metric.request.total".equals(meter.getId().getName())
                && new ArrayList<Tag>() {{
            add(Tag.of("result", "SUCCESS"));
        }}.equals(meter.getId().getTags())));
    }

    @Test
    void testProxyWithEmptyAnnotationTest() {
        TestAspectInterface proxy = (TestAspectInterface) Proxy.newProxyInstance(TestAspectInterface.class.getClassLoader(), new Class[]{TestAspectInterface.class},
                timeMeasurementManager.provideTimeMeasurementInvocationHandler(new TestAspectService()));
        proxy.secondTestMethod();
        assertRegistryWithEmptyAnnotationTest();
    }

    @Test
    void testBeanWithEmptyAnnotationTest() {
        timeMeasure.secondTestMethod();
        assertRegistryWithEmptyAnnotationTest();
    }

    private void assertRegistryWithEmptyAnnotationTest() {
        List<Meter> meters = meterRegistry.getMeters().stream().collect(Collectors.toList());

        Assertions.assertTrue(meters.stream().anyMatch(meter -> "dbaas.time.metric".equals(meter.getId().getName())
                && Collections.emptyList().equals(meter.getId().getTags())));
        Assertions.assertTrue(meters.stream().anyMatch(meter -> "dbaas.time.metric.request.total".equals(meter.getId().getName())
                && new ArrayList<Tag>() {{
            add(Tag.of("result", "SUCCESS"));
        }}.equals(meter.getId().getTags())));
    }

    @Test
    void testProxyWithValueAndTag() {
        TestAspectInterface proxy = (TestAspectInterface) Proxy.newProxyInstance(TestAspectInterface.class.getClassLoader(), new Class[]{TestAspectInterface.class},
                timeMeasurementManager.provideTimeMeasurementInvocationHandler(new TestAspectService()));
        proxy.thirdTestMethod();
        assertRegistryWithValueAndTag();
    }

    @Test
    void testBeanWithValueAndTag() {
        timeMeasure.thirdTestMethod();
        assertRegistryWithValueAndTag();
    }

    private void assertRegistryWithValueAndTag() {
        List<Meter> meters = meterRegistry.getMeters().stream().collect(Collectors.toList());

        Assertions.assertTrue(meters.stream().anyMatch(meter -> "some.metric".equals(meter.getId().getName())
                && new ArrayList<Tag>() {{
            add(Tag.of("some-tag1", "some-value1"));
        }}.equals(meter.getId().getTags())));
        Assertions.assertTrue(meters.stream().anyMatch(meter -> "some.metric.request.total".equals(meter.getId().getName())
                && new ArrayList<Tag>() {{
            add(Tag.of("result", "SUCCESS"));
            add(Tag.of("some-tag1", "some-value1"));
        }}.equals(meter.getId().getTags())));
    }

    @Test
    void testProxyWithValueAndClassFieldTag() {
        TestAspectInterface proxy = (TestAspectInterface) Proxy.newProxyInstance(TestAspectInterface.class.getClassLoader(), new Class[]{TestAspectInterface.class},
                timeMeasurementManager.provideTimeMeasurementInvocationHandler(new TestAspectService()));
        proxy.forthTestMethod();
        assertRegistryWithValueAndClassFieldTag();
    }

    @Test
    void testBeanWithValueAndClassFieldTag() {
        timeMeasure.forthTestMethod();
        assertRegistryWithValueAndClassFieldTag();
    }

    private void assertRegistryWithValueAndClassFieldTag() {
        List<Meter> meters = meterRegistry.getMeters().stream().collect(Collectors.toList());

        Assertions.assertTrue(meters.stream().anyMatch(meter -> "some.metric".equals(meter.getId().getName())
                && new ArrayList<Tag>() {{
            add(Tag.of("testDoubleField", "1.01"));
            add(Tag.of("testIntField", "1"));
            add(Tag.of("testIntegerField", "1"));
            add(Tag.of("testStringField", "testStringValue"));
        }}.equals(meter.getId().getTags())));
        Assertions.assertTrue(meters.stream().anyMatch(meter -> "some.metric.request.total".equals(meter.getId().getName())
                && new ArrayList<Tag>() {{
            add(Tag.of("result", "SUCCESS"));
            add(Tag.of("testDoubleField", "1.01"));
            add(Tag.of("testIntField", "1"));
            add(Tag.of("testIntegerField", "1"));
            add(Tag.of("testStringField", "testStringValue"));
        }}.equals(meter.getId().getTags())));
    }

    @Test
    void testProxyWithFullSetOfParameters() {
        TestAspectInterface proxy = (TestAspectInterface) Proxy.newProxyInstance(TestAspectInterface.class.getClassLoader(), new Class[]{TestAspectInterface.class},
                timeMeasurementManager.provideTimeMeasurementInvocationHandler(new TestAspectService()));
        proxy.fifthTestMethod();
        assertRegistryWithFullSetOfParameters();
    }

    @Test
    void testBeanWithFullSetOfParameters() {
        timeMeasure.fifthTestMethod();
        assertRegistryWithFullSetOfParameters();
    }

    private void assertRegistryWithFullSetOfParameters() {
        List<Meter> meters = meterRegistry.getMeters().stream().collect(Collectors.toList());

        Assertions.assertTrue(meters.stream().anyMatch(meter -> "some.metric".equals(meter.getId().getName())
                && new ArrayList<Tag>() {{
            add(Tag.of("some-tag1", "some-value1"));
            add(Tag.of("testDoubleField", "1.01"));
            add(Tag.of("testIntField", "1"));
            add(Tag.of("testIntegerField", "1"));
            add(Tag.of("testStringField", "testStringValue"));
        }}.equals(meter.getId().getTags())));
        Assertions.assertTrue(meters.stream().anyMatch(meter -> "some.metric.request.total".equals(meter.getId().getName())
                && new ArrayList<Tag>() {{
            add(Tag.of("result", "SUCCESS"));
            add(Tag.of("some-tag1", "some-value1"));
            add(Tag.of("testDoubleField", "1.01"));
            add(Tag.of("testIntField", "1"));
            add(Tag.of("testIntegerField", "1"));
            add(Tag.of("testStringField", "testStringValue"));
        }}.equals(meter.getId().getTags())));
    }
}
