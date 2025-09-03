package com.netcracker.cloud.dbaas.entity.h2;

import com.netcracker.cloud.dbaas.entity.shared.AbstractDbResource;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Entity(name = "DbResource")
@Table(name = "db_resources")
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class DbResource extends AbstractDbResource {

    public com.netcracker.cloud.dbaas.entity.pg.DbResource asPgEntity() {
        com.netcracker.cloud.dbaas.entity.pg.DbResource copy = new com.netcracker.cloud.dbaas.entity.pg.DbResource(this.kind, this.name);
        copy.setId(this.id);
        return copy;
    }

}
