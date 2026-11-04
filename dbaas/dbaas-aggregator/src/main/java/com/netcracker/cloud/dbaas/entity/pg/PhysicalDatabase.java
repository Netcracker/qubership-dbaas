package com.netcracker.cloud.dbaas.entity.pg;

import com.netcracker.cloud.dbaas.entity.shared.AbstractPhysicalDatabase;
import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity(name = "PhysicalDatabase")
@Table(name = "physical_database")
public class PhysicalDatabase extends AbstractPhysicalDatabase {

    @OneToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "adapter_external_adapter_id")
    private ExternalAdapterRegistrationEntry adapter;

    public com.netcracker.cloud.dbaas.entity.h2.PhysicalDatabase asH2Entity() {
        com.netcracker.cloud.dbaas.entity.h2.PhysicalDatabase copy = new com.netcracker.cloud.dbaas.entity.h2.PhysicalDatabase();
        copyStateTo(copy);
        if (adapter != null) {
            copy.setAdapter(this.adapter.asH2Entity());
        }
        return copy;
    }
}
