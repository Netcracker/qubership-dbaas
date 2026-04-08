package com.netcracker.cloud.dbaas.controller.abstact;

import com.netcracker.cloud.dbaas.exceptions.ForbiddenDeleteOperationException;
import com.netcracker.cloud.dbaas.service.DbaaSHelper;
import jakarta.inject.Inject;

public abstract class AbstractController {
    @Inject
    protected DbaaSHelper dbaaSHelper;

    protected void assertNotProdMode() {
        if (dbaaSHelper.isProductionMode()) {
            throw new ForbiddenDeleteOperationException();
        }
    }
}
