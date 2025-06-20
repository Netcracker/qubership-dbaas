package org.qubership.cloud.dbaas.dto.declarative;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.qubership.cloud.dbaas.serializer.TaskStateSerializer;
import org.qubership.core.scheduler.po.task.TaskState;
import lombok.Data;

import java.util.List;

@Data
public class DbDeclarationResponse {

    @JsonProperty("spec")
    private List<SpecDeclarativeResponseItem> spec;

    @JsonProperty("state")
    @JsonSerialize(using = TaskStateSerializer.class)
    private TaskState state;

}