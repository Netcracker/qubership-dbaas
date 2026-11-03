package com.netcracker.cloud.dbaas.entity.h2;

import com.netcracker.cloud.dbaas.entity.shared.AbstractDbState;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@Entity(name = "DbState")
@Table(name = "database_state_info")
public class DbState extends AbstractDbState {

    public com.netcracker.cloud.dbaas.entity.pg.DbState asPgEntity() {
        com.netcracker.cloud.dbaas.entity.pg.DbState clone = new com.netcracker.cloud.dbaas.entity.pg.DbState();
        copyStateTo(clone);
        return clone;
    }
}
