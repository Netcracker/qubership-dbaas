package org.qubership.cloud.dbaas.service;

import org.qubership.core.scheduler.po.task.TaskState;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.qubership.cloud.dbaas.service.BlueGreenStatusUtil.*;

class BlueGreenStatusUtilTest {

    @Test
    void taskStatusToStringInProgress() {
        Assertions.assertEquals(IN_PROGRESS_STATE, BlueGreenStatusUtil.taskStatusToString(TaskState.IN_PROGRESS));
    }

    @Test
    void taskStatusToStringCompleted() {
        Assertions.assertEquals(COMPLETED_STATE, BlueGreenStatusUtil.taskStatusToString(TaskState.COMPLETED));
    }

    @Test
    void taskStatusToStringNotStarted() {
        Assertions.assertEquals(NOT_STARTED_STATE, BlueGreenStatusUtil.taskStatusToString(TaskState.NOT_STARTED));
    }

    @Test
    void taskStatusToStringFailed() {
        Assertions.assertEquals(FAILED_STATE, BlueGreenStatusUtil.taskStatusToString(TaskState.FAILED));
    }

    @Test
    void taskStatusToStringTerminated() {
        Assertions.assertEquals(TERMINATED_STATE, BlueGreenStatusUtil.taskStatusToString(TaskState.TERMINATED));
    }

    @Test
    void taskStatusToStringNull() {
        Assertions.assertNull(BlueGreenStatusUtil.taskStatusToString(null));
    }
}