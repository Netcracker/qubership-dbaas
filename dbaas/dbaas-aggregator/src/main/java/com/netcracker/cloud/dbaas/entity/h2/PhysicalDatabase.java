package org.qubership.cloud.dbaas.entity.h2;

import org.qubership.cloud.dbaas.entity.shared.AbstractPhysicalDatabase;
import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity(name = "PhysicalDatabase")
@Table(name = "physical_database")
public class PhysicalDatabase extends AbstractPhysicalDatabase {

    @OneToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "adapter_external_adapter_id")
    private ExternalAdapterRegistrationEntry adapter;

    public org.qubership.cloud.dbaas.entity.pg.PhysicalDatabase asPgEntity() {
        org.qubership.cloud.dbaas.entity.pg.PhysicalDatabase copy = new org.qubership.cloud.dbaas.entity.pg.PhysicalDatabase();
        copy.setId(this.id);
        copy.setPhysicalDatabaseIdentifier(this.physicalDatabaseIdentifier);
        copy.setGlobal(this.global);
        if (adapter != null) {
            copy.setAdapter(this.adapter.asPgEntity());
        }
        copy.setLabels(this.labels);
        copy.setType(this.type);
        copy.setRegistrationDate(this.registrationDate);
        copy.setRoles(this.roles);
        copy.setFeatures(this.features);
        copy.setUnidentified(this.unidentified);
        copy.setRoHost(this.roHost);
        return copy;
    }
}
