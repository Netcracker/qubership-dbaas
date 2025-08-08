package com.netcracker.cloud.dbaas.dto.bluegreen;


import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.netcracker.cloud.dbaas.serializer.TaskStateSerializer;
import com.netcracker.core.scheduler.po.task.TaskState;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
@Data
public class OperationStatusResponse extends BlueGreenResponse {
    @JsonSerialize(using = TaskStateSerializer.class)
    TaskState status;

    List<OperationDetail> operationDetails;

}
