package org.qubership.cloud.dbaas.entity.h2;

import org.qubership.cloud.dbaas.entity.shared.AbstractExternalAdapterRegistrationEntry;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@Entity(name = "ExternalAdapterRegistrationEntry")
@Table(name = "external_adapter_registration")
public class ExternalAdapterRegistrationEntry extends AbstractExternalAdapterRegistrationEntry {

    public org.qubership.cloud.dbaas.entity.pg.ExternalAdapterRegistrationEntry asPgEntity() {
        org.qubership.cloud.dbaas.entity.pg.ExternalAdapterRegistrationEntry copy = new org.qubership.cloud.dbaas.entity.pg.ExternalAdapterRegistrationEntry();
        copy.setAdapterId(this.adapterId);
        copy.setAddress(this.address);
        copy.setHttpBasicCredentials(this.httpBasicCredentials);
        copy.setSupportedVersion(this.supportedVersion);
        copy.setApiVersions(this.apiVersions);
        return copy;
    }

}
