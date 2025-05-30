package org.qubership.cloud.dbaas.dto.declarative;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.qubership.cloud.dbaas.serializer.TaskStateSerializer;
import org.qubership.core.scheduler.po.task.TaskState;
import lombok.Data;
import org.jetbrains.annotations.Nullable;

@Data
public class DeclarativeCreationResponse {

    @JsonProperty("DbPolicy")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private DbPolicyResponse dbPolicy;

    @JsonProperty("DatabaseDeclaration")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private DbDeclarationResponse databaseDeclaration;

    @JsonProperty("state")
    @JsonSerialize(using = TaskStateSerializer.class)
    private TaskState state;

    @JsonProperty("trackingId")
    @Nullable
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String trackingId;

}