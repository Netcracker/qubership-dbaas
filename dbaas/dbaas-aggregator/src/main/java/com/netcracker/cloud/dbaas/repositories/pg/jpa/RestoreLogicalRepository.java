package com.netcracker.cloud.dbaas.repositories.pg.jpa;

import com.netcracker.cloud.dbaas.entity.pg.backupV2.RestoreLogical;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.UUID;

@Transactional
@ApplicationScoped
public class RestoreLogicalRepository implements PanacheRepositoryBase<RestoreLogical, UUID> {
}
