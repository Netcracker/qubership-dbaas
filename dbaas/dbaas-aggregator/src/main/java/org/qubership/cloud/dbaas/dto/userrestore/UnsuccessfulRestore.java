package org.qubership.cloud.dbaas.dto.userrestore;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;


@Data
@AllArgsConstructor
@NoArgsConstructor
public class UnsuccessfulRestore {
    private Map<String, Object> connectionProperties;
    private String errorMessage;
}
