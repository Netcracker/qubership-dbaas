package com.netcracker.cloud.dbaas.dto;

import com.netcracker.cloud.dbaas.service.PasswordEncryption;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import static com.netcracker.cloud.dbaas.dto.ConnectionDescription.FieldTypeEnum.PASSWORD;

@Data
@AllArgsConstructor
@EqualsAndHashCode
@NoArgsConstructor
@Schema(description = "Describes the fields of connection properties, for example which of them are protected")
public class ConnectionDescription implements Serializable {
    public static final ConnectionDescription DEFAULT_CONNECTION_DESCRIPTION = new ConnectionDescription(
            new HashMap<String, FieldDescription>() {{
                put(PasswordEncryption.PASSWORD_FIELD, new FieldDescription(PASSWORD));
            }}
    );

    @Schema(description = "Fields of the connection properties")
    Map<String, FieldDescription> fields;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class FieldDescription implements Serializable {
        @Schema(description = "Type of the field")
        FieldTypeEnum type;
    }

    public enum FieldTypeEnum {
        PASSWORD
    }

    public ConnectionDescription(ConnectionDescription connectionDescription) {
        this.fields = connectionDescription.getFields() != null ? new HashMap<>(connectionDescription.getFields()) : null;
    }
}
