package com.netcracker.cloud.dbaas.repositories.pg.jpa;

import com.netcracker.cloud.dbaas.entity.pg.LogicalDbOperationError;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.UUID;

@ApplicationScoped
public class LogicalDbOperationErrorRepository implements PanacheRepositoryBase<LogicalDbOperationError, UUID> {
}
