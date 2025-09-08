package com.netcracker.cloud.dbaas.dto.oidc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

@JsonIgnoreProperties(ignoreUnknown = true)
public class OidcConfig {
    @Getter
	@JsonProperty("jwks_uri")
	private String jwks_uri;

    public OidcConfig(String jwks_uri) {
		this.jwks_uri = jwks_uri;
	}

	public OidcConfig() {
	}
}
