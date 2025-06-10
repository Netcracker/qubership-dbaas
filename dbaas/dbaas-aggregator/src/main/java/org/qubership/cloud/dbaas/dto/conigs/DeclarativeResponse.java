package org.qubership.cloud.dbaas.dto.conigs;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.qubership.core.scheduler.po.task.TaskState;
import lombok.Data;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

@Data
public class DeclarativeResponse {
    private String status;

    @Nullable
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String trackingId;

    private List<Condition> conditions = new ArrayList<>();

    public void setStatus(TaskState status) {
        this.status = normalizeStateName(status);
    }

    @Data
    public static class Condition {
        public static final String VALIDATED = "Validated";
        public static final String ROLES_CREATED = "SecurityRolesCreated";
        public static final String DB_CREATED = "DataBaseCreated";

        private String type;
        private String state;

        @Nullable
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String reason;

        @Nullable
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String message;

        public Condition(String type, TaskState state) {
            this(type, state, null, null);
        }

        public Condition(String type, TaskState state, @Nullable String reason, @Nullable String message) {
            this.type = type;
            this.state = normalizeStateName(state);
            this.reason = reason;
            this.message = message;
        }
    }

    private static String normalizeStateName(TaskState state) {
        return switch (state) {
            case NOT_STARTED -> "NOT_STARTED";
            case IN_PROGRESS -> "IN_PROGRESS";
            case COMPLETED -> "COMPLETED";
            case FAILED -> "FAILED";
            case TERMINATED -> "TERMINATED";
        };
    }
}
