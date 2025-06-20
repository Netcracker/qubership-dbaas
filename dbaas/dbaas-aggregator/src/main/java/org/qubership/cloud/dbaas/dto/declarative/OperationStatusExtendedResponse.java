package org.qubership.cloud.dbaas.dto.declarative;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.qubership.core.scheduler.po.model.pojo.ProcessInstanceImpl;
import org.qubership.core.scheduler.po.model.pojo.TaskInstanceImpl;
import org.qubership.core.scheduler.po.task.TaskState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;

import java.util.List;
import java.util.Map;

@Data
public class OperationStatusExtendedResponse {
    private String status;
    private String message;
    private OperationDetails operationDetails;

    public void setStatus(ProcessInstanceImpl process) {
        this.status = normalizeStateName(process);
    }

    @Data
    @AllArgsConstructor
    public static class OperationDetails {
        private List<TaskDetails> tasks;
    }

    @Data
    @Builder
    public static class TaskDetails {
        private String taskId;
        private String taskName;
        private OperationState state;
        private Map<String, Object> classifier;
        private String type;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String backupId;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String restoreId;

        @Getter
        public static class OperationState {
            private final String status;
            private final String description;

            public OperationState(TaskInstanceImpl task, String description) {
                this.status = normalizeStateName(task);
                this.description = description;
            }
        }
    }

    private static String normalizeStateName(TaskInstanceImpl task) {
        return normalizeStateName(task.getState(), isWaitingForResources(task));
    }

    private static String normalizeStateName(ProcessInstanceImpl process) {
        return normalizeStateName(process.getState(), isWaitingForResources(process));
    }

    private static String normalizeStateName(TaskState state, boolean waitingForResources) {
        return switch (state) {
            case NOT_STARTED -> "NOT_STARTED";
            case IN_PROGRESS -> waitingForResources ? "WAITING_FOR_RESOURCES" : "IN_PROGRESS";
            case COMPLETED -> "COMPLETED";
            case FAILED -> "FAILED";
            case TERMINATED -> "TERMINATED";
        };
    }

    private static boolean isWaitingForResources(TaskInstanceImpl task) {
        return TaskState.IN_PROGRESS.equals(task.getState())
                && Boolean.parseBoolean((String) task.getContext().get("waitingForResources"));
    }

    private static boolean isWaitingForResources(ProcessInstanceImpl process) {
        return process.getTasks().stream().anyMatch(OperationStatusExtendedResponse::isWaitingForResources);
    }
}
