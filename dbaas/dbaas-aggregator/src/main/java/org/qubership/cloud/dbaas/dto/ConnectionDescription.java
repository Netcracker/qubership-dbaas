package org.qubership.cloud.dbaas.dto;

import org.qubership.cloud.dbaas.service.PasswordEncryption;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import static org.qubership.cloud.dbaas.dto.ConnectionDescription.FieldTypeEnum.PASSWORD;

@Data
@AllArgsConstructor
@EqualsAndHashCode
@NoArgsConstructor
public class ConnectionDescription implements Serializable {
    public static final ConnectionDescription DEFAULT_CONNECTION_DESCRIPTION = new ConnectionDescription(
            new HashMap<String, FieldDescription>() {{
                put(PasswordEncryption.PASSWORD_FIELD, new FieldDescription(PASSWORD));
            }}
    );

    Map<String, FieldDescription> fields;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class FieldDescription implements Serializable {
        FieldTypeEnum type;
    }

    public enum FieldTypeEnum {
        PASSWORD
    }

    public ConnectionDescription(ConnectionDescription connectionDescription) {
        this.fields = connectionDescription.getFields() != null ? new HashMap<>(connectionDescription.getFields()) : null;
    }
}
