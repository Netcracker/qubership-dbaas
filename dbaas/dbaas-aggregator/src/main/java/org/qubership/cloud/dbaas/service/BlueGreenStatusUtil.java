package org.qubership.cloud.dbaas.service;

import org.qubership.core.scheduler.po.task.TaskState;

public class BlueGreenStatusUtil {

    public static final String NOT_STARTED_STATE = "not started";
    public static final String COMPLETED_STATE = "completed";
    public static final String FAILED_STATE = "failed";
    public static final String IN_PROGRESS_STATE = "in progress";
    public static final String TERMINATED_STATE = "terminated";

    private BlueGreenStatusUtil() {
    }

    public static String taskStatusToString(TaskState state) {
        if (TaskState.NOT_STARTED.equals(state)) {
            return NOT_STARTED_STATE;
        } else if (TaskState.COMPLETED.equals(state)) {
            return COMPLETED_STATE;
        } else if (TaskState.FAILED.equals(state)) {
            return FAILED_STATE;
        } else if (TaskState.IN_PROGRESS.equals(state)) {
            return IN_PROGRESS_STATE;
        } else if (TaskState.TERMINATED.equals(state)) {
            return TERMINATED_STATE;
        }
        return null;
    }
}
