package org.qubership.cloud.dbaas.service.processengine.processes;

import org.qubership.cloud.dbaas.service.processengine.tasks.DeleteBackupTask;
import org.qubership.cloud.dbaas.service.processengine.tasks.NewDatabaseTask;
import org.qubership.cloud.dbaas.service.processengine.tasks.UpdateBgStateTask;
import org.qubership.core.scheduler.po.model.pojo.ProcessInstanceImpl;
import org.qubership.core.scheduler.po.model.pojo.TaskInstanceImpl;
import org.qubership.core.scheduler.po.task.TaskState;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class ProcessWrapper {
    private final List<TaskInstanceImpl> tasks;
    private final List<TaskInstanceImpl> terminalTasks;

    public ProcessWrapper(ProcessInstanceImpl process) {
        this.tasks = process.getTasks().stream()
                .filter(task -> !UpdateBgStateTask.class.getName().equals(task.getType()))
                .toList();
        this.terminalTasks = tasks.stream()
                .filter(task -> NewDatabaseTask.class.getName().equals(task.getType()) || DeleteBackupTask.class.getName().equals(task.getType()))
                .toList();
    }

    @NotNull
    public List<TaskInstanceImpl> getConfigTasks() {
        return tasks;
    }

    @NotNull
    public List<TaskInstanceImpl> getFailedTasks() {
        return tasks.stream().filter(task -> TaskState.FAILED.equals(task.getState())).toList();
    }

    @NotNull
    public List<TaskInstanceImpl> getWaitingTasks() {
        return tasks.stream().filter(task -> TaskState.IN_PROGRESS.equals(task.getState())
                && Boolean.parseBoolean((String) task.getContext().get("waitingForResources"))).toList();
    }

    public long getCompletedTasksCount() {
        return tasks.stream().filter(task -> TaskState.COMPLETED.equals(task.getState())).count();
    }

    public long getAllTasksCount() {
        return tasks.size();
    }

    @NotNull
    public List<TaskInstanceImpl> getTerminalTasks() {
        return terminalTasks;
    }

    public long getCompletedDbCount() {
        return terminalTasks.stream()
                .filter(task -> TaskState.COMPLETED.equals(task.getState()))
                .count();
    }

    public long getAllDbCount() {
        return terminalTasks.size();
    }
}
