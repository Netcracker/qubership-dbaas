package org.qubership.cloud.dbaas.entity.pg;

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

    public org.qubership.cloud.dbaas.entity.h2.PhysicalDatabase asH2Entity() {
        org.qubership.cloud.dbaas.entity.h2.PhysicalDatabase copy = new org.qubership.cloud.dbaas.entity.h2.PhysicalDatabase();
        copy.setId(this.id);
        copy.setPhysicalDatabaseIdentifier(this.physicalDatabaseIdentifier);
        copy.setGlobal(this.global);
        if (adapter != null) {
            copy.setAdapter(this.adapter.asH2Entity());
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
