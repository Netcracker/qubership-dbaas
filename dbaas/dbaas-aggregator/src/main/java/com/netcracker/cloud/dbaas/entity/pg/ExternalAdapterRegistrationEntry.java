package com.netcracker.cloud.dbaas.entity.pg;

import com.netcracker.cloud.dbaas.dto.HttpBasicCredentials;
import com.netcracker.cloud.dbaas.entity.shared.AbstractExternalAdapterRegistrationEntry;
import com.netcracker.cloud.dbaas.dto.v3.ApiVersion;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@Entity(name = "ExternalAdapterRegistrationEntry")
@Table(name = "external_adapter_registration")
public class ExternalAdapterRegistrationEntry extends AbstractExternalAdapterRegistrationEntry {

    public ExternalAdapterRegistrationEntry(String adapterId, String address, HttpBasicCredentials httpBasicCredentials, String supportedVersion, ApiVersion apiVersions) {
        super(adapterId, address, httpBasicCredentials, supportedVersion, apiVersions);
    }

    public com.netcracker.cloud.dbaas.entity.h2.ExternalAdapterRegistrationEntry asH2Entity() {
        com.netcracker.cloud.dbaas.entity.h2.ExternalAdapterRegistrationEntry copy = new com.netcracker.cloud.dbaas.entity.h2.ExternalAdapterRegistrationEntry();
        copy.setAdapterId(this.adapterId);
        copy.setAddress(this.address);
        copy.setHttpBasicCredentials(this.httpBasicCredentials);
        copy.setSupportedVersion(this.supportedVersion);
        copy.setApiVersions(this.apiVersions);
        return copy;
    }
}
