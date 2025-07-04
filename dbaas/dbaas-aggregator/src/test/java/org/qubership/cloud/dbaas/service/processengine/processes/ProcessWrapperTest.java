package org.qubership.cloud.dbaas.service.processengine.processes;

import org.qubership.cloud.dbaas.service.processengine.tasks.*;
import org.qubership.core.scheduler.po.model.pojo.ProcessInstanceImpl;
import org.qubership.core.scheduler.po.model.pojo.TaskInstanceImpl;
import org.qubership.core.scheduler.po.task.TaskState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.qubership.cloud.dbaas.service.processengine.Const.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

class ProcessWrapperTest {
    private ProcessInstanceImpl createMockProcess() {
        String processId = "process_id";
        List<TaskInstanceImpl> tasks = new ArrayList<>();

        TaskInstanceImpl bgTask = new TaskInstanceImpl("task_id1_upd", UPDATE_BG_STATE_TASK, UpdateBgStateTask.class.getName(), processId);
        bgTask.setState(TaskState.IN_PROGRESS);
        tasks.add(bgTask);

        TaskInstanceImpl backupTask = new TaskInstanceImpl("task_id2_bac", BACKUP_TASK, BackupDatabaseTask.class.getName(), processId);
        backupTask.setState(TaskState.COMPLETED);
        tasks.add(backupTask);

        TaskInstanceImpl restoreTask = new TaskInstanceImpl("task_id3_res", RESTORE_TASK, RestoreDatabaseTask.class.getName(), processId);
        restoreTask.setState(TaskState.COMPLETED);
        tasks.add(restoreTask);

        TaskInstanceImpl deleteBackupTask = new TaskInstanceImpl("task_id4_del", DELETE_BACKUP_TASK, DeleteBackupTask.class.getName(), processId);
        restoreTask.setState(TaskState.IN_PROGRESS);
        tasks.add(deleteBackupTask);

        TaskInstanceImpl createTask = new TaskInstanceImpl("task_id5_new", NEW_DATABASE_TASK, NewDatabaseTask.class.getName(), processId);
        createTask.setState(TaskState.FAILED);
        tasks.add(createTask);

        TaskInstanceImpl createTask2 = new TaskInstanceImpl("task_id6_new", NEW_DATABASE_TASK, NewDatabaseTask.class.getName(), processId);
        createTask2.setState(TaskState.COMPLETED);
        tasks.add(createTask2);

        ProcessInstanceImpl processInstance = mock(ProcessInstanceImpl.class);
        doReturn(tasks).when(processInstance).getTasks();

        return processInstance;
    }

    @Test
    void getConfigTasks() {
        ProcessInstanceImpl mockProcess = createMockProcess();
        ProcessWrapper processWrapper = new ProcessWrapper(mockProcess);
        List<TaskInstanceImpl> configTasks = processWrapper.getConfigTasks();
        assertEquals(5, configTasks.size());
        assertFalse(configTasks.stream().anyMatch(taskInstance -> taskInstance.getType().equals(UpdateBgStateTask.class.getName())));
    }

    @Test
    void getFailedTasks() {
        ProcessInstanceImpl mockProcess = createMockProcess();
        ProcessWrapper processWrapper = new ProcessWrapper(mockProcess);
        List<TaskInstanceImpl> failedTasks = processWrapper.getFailedTasks();
        assertEquals(1, failedTasks.size());
        assertEquals(NEW_DATABASE_TASK, failedTasks.getFirst().getName());
    }

    @Test
    void getCompletedTasksCount() {
        ProcessInstanceImpl mockProcess = createMockProcess();
        ProcessWrapper processWrapper = new ProcessWrapper(mockProcess);
        long completedTasksCount = processWrapper.getCompletedTasksCount();
        assertEquals(2, completedTasksCount);
    }

    @Test
    void getAllTasksCount() {
        ProcessInstanceImpl mockProcess = createMockProcess();
        ProcessWrapper processWrapper = new ProcessWrapper(mockProcess);
        long allTasksCount = processWrapper.getAllTasksCount();
        assertEquals(5, allTasksCount);
    }

    @Test
    void getTerminalTasks() {
        ProcessInstanceImpl mockProcess = createMockProcess();
        ProcessWrapper processWrapper = new ProcessWrapper(mockProcess);
        List<TaskInstanceImpl> terminalTasks = processWrapper.getTerminalTasks();
        assertEquals(3, terminalTasks.size());
        assertTrue(terminalTasks.stream().anyMatch(task -> "task_id4_del".equals(task.getId())));
        assertTrue(terminalTasks.stream().anyMatch(task -> "task_id5_new".equals(task.getId())));
        assertTrue(terminalTasks.stream().anyMatch(task -> "task_id6_new".equals(task.getId())));
    }

    @Test
    void getCompletedDbCount() {
        ProcessInstanceImpl mockProcess = createMockProcess();
        ProcessWrapper processWrapper = new ProcessWrapper(mockProcess);
        long completedDbCount = processWrapper.getCompletedDbCount();
        assertEquals(1, completedDbCount);
    }

    @Test
    void getAllDbCount() {
        ProcessInstanceImpl mockProcess = createMockProcess();
        ProcessWrapper processWrapper = new ProcessWrapper(mockProcess);
        long allDbCount = processWrapper.getAllDbCount();
        assertEquals(3, allDbCount);
    }
}