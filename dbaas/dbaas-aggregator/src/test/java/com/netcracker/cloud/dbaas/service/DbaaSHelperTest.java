package org.qubership.cloud.dbaas.service;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class DbaaSHelperTest {

    @Test
    void testIsDevModeFalse() {
        DbaaSHelper dbaaSHelper = new DbaaSHelper(true, "local");
        Assertions.assertTrue(dbaaSHelper.isProductionMode());
    }

    @Test
    void testIsDevModeTrue() {
        DbaaSHelper dbaaSHelper = new DbaaSHelper(false, "local");
        Assertions.assertFalse(dbaaSHelper.isProductionMode());
    }
}
