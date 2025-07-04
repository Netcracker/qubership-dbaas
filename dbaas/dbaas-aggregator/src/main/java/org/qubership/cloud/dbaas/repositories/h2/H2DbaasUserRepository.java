package org.qubership.cloud.dbaas.repositories.h2;

import org.qubership.cloud.dbaas.entity.h2.DbaasUser;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.UUID;

@ApplicationScoped
public class H2DbaasUserRepository implements PanacheRepositoryBase<DbaasUser, UUID> {
}
