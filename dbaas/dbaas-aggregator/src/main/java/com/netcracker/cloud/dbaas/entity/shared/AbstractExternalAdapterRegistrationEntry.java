package org.qubership.cloud.dbaas.entity.shared;

import org.qubership.cloud.dbaas.converter.ApiVersionConverter;
import org.qubership.cloud.dbaas.converter.HttpBasicCredentialsConverter;
import org.qubership.cloud.dbaas.dto.HttpBasicCredentials;
import org.qubership.cloud.dbaas.dto.v3.ApiVersion;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@MappedSuperclass
public abstract class AbstractExternalAdapterRegistrationEntry {

    @Id
    @Column(name = "adapter_id")
    protected String adapterId;

    protected String address;

    @Column(name = "http_basic_credentials")
    @Convert(converter = HttpBasicCredentialsConverter.class)
    protected HttpBasicCredentials httpBasicCredentials;

    @Column(name = "supported_version")
    protected String supportedVersion;

    @Column(name = "api_versions")
    @Convert(converter = ApiVersionConverter.class)
    protected ApiVersion apiVersions;
}
