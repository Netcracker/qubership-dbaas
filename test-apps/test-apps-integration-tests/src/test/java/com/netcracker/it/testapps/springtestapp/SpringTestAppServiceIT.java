package com.netcracker.it.testapps.springtestapp;

import com.netcracker.it.testapps.SampleServiceIT;

public class SpringTestAppServiceIT extends SampleServiceIT {

    @Override
    protected String serviceName() {
        return "spring-test-app-service";
    }
}
