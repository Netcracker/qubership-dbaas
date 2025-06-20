package org.qubership.cloud.dbaas.dto.bluegreen;

import lombok.Data;

@Data
public class AsyncResponse extends BlueGreenResponse {
    String trackingId;

}
