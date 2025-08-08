package com.netcracker.cloud.dbaas.dto.bluegreen;

import lombok.Data;

@Data
public class AsyncResponse extends BlueGreenResponse {
    String trackingId;

}
