package org.qubership.cloud.dbaas.dto.bluegreen;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.qubership.cloud.dbaas.serializer.TaskStateSerializer;
import org.qubership.core.scheduler.po.task.TaskState;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class OperationDetail {
    private String taskId;

    @JsonSerialize(using = TaskStateSerializer.class)
    private TaskState status;

    private Map<String, Object> classifier;
    private String type;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String backupId;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String restoreId;
}
