package org.qubership.cloud.dbaas.service;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class DbaaSHelperTest {

    @Test
    public void testIsDevModeFalse() {
        DbaaSHelper dbaaSHelper = new DbaaSHelper(true, "local");
        Assertions.assertTrue(dbaaSHelper.isProductionMode());
    }

    @Test
    public void testIsDevModeTrue() {
        DbaaSHelper dbaaSHelper = new DbaaSHelper(false, "local");
        Assertions.assertFalse(dbaaSHelper.isProductionMode());
    }
}
