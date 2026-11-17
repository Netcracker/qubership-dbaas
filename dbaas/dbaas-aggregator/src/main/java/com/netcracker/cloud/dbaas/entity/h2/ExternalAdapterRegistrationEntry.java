package com.netcracker.cloud.dbaas.entity.h2;

import com.netcracker.cloud.dbaas.entity.shared.AbstractExternalAdapterRegistrationEntry;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@Entity(name = "ExternalAdapterRegistrationEntry")
@Table(name = "external_adapter_registration")
public class ExternalAdapterRegistrationEntry extends AbstractExternalAdapterRegistrationEntry {

    public com.netcracker.cloud.dbaas.entity.pg.ExternalAdapterRegistrationEntry asPgEntity() {
        com.netcracker.cloud.dbaas.entity.pg.ExternalAdapterRegistrationEntry copy = new com.netcracker.cloud.dbaas.entity.pg.ExternalAdapterRegistrationEntry();
        copyStateTo(copy);
        return copy;
    }

}
