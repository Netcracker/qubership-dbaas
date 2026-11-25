package com.netcracker.it.testapps.quarkustestapp;

import com.netcracker.it.testapps.SampleServiceIT;

public class QuarkusTestAppServiceIT extends SampleServiceIT {

    @Override
    protected String serviceName() {
        return "quarkus-test-app-service";
    }
}
