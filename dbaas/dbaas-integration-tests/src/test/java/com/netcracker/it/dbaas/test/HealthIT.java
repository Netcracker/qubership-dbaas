package com.netcracker.it.dbaas.test;

import com.netcracker.it.dbaas.entity.AggregatorHealth;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Slf4j
public class HealthIT extends AbstractIT {

    @Test
    @Tag("Smoke")
    public void health() throws IOException {
        AggregatorHealth health = getHealth();
        assertEquals("UP", health.getStatus());
        health.getComponents()
                .values()
                .forEach(healthItem -> assertEquals("UP", healthItem.getStatus()));
    }
}
