package com.netcracker.cloud.dbaas.dto.bluegreen;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

import lombok.Data;

@Data
public class AsyncResponse extends BlueGreenResponse {
    String trackingId;

}
