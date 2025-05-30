package org.qubership.cloud.dbaas.entity.pg;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.qubership.cloud.dbaas.entity.shared.AbstractDbResource;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.NonNull;


@NoArgsConstructor
@Entity(name = "DbResource")
@Table(name = "db_resources")
@EqualsAndHashCode(callSuper = true)
public class DbResource extends AbstractDbResource {

    public DbResource(@JsonProperty("kind") @NonNull String kind, @JsonProperty("name") @NonNull String name) {
        super(kind, name);
    }

    public DbResource(DbResource dbResource) {
        super(dbResource.getKind(), dbResource.getName());
    }

    public org.qubership.cloud.dbaas.entity.h2.DbResource asH2Entity() {
        org.qubership.cloud.dbaas.entity.h2.DbResource copy = new org.qubership.cloud.dbaas.entity.h2.DbResource();
        copy.setKind(this.kind);
        copy.setName(this.name);
        copy.setId(this.id);
        return copy;
    }

}
