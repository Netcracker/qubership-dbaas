package org.qubership.cloud.dbaas.service.processengine.tasks;

import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import org.qubership.cloud.context.propagation.core.ContextManager;
import org.qubership.cloud.framework.contexts.xrequestid.XRequestIdContextObject;
import org.qubership.core.scheduler.po.DataContext;
import org.qubership.core.scheduler.po.context.TaskExecutionContext;
import org.qubership.core.scheduler.po.task.templates.AbstractProcessTask;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

import static org.qubership.cloud.framework.contexts.xrequestid.XRequestIdContextObject.X_REQUEST_ID;

@Slf4j
public abstract class AbstractDbaaSTask extends AbstractProcessTask implements Serializable {

    protected AbstractDbaaSTask(String name) {
        super(name);
    }

    @Override
    public void executeInternal(TaskInstance<TaskExecutionContext> taskInstance, ExecutionContext executionContext) {
        DataContext dataContext = taskInstance.getData().getTaskInstance().getContext();
        try {
            updateState(dataContext, "Initialization");
            ContextManager.set(X_REQUEST_ID, new XRequestIdContextObject((String) dataContext.get(X_REQUEST_ID)));
            log.debug("Start '{}' task", super.getName());
            executeTask(dataContext);
            updateState(dataContext, "Done");
        } catch (Throwable e) {
            updateState(dataContext, "Error: " + e.getMessage(), false);
            throw e;
        } finally {
            ContextManager.clear(X_REQUEST_ID);
        }
    }

    protected abstract void executeTask(DataContext context);

    protected static void updateState(DataContext context, String stateDescription) {
        updateState(context, stateDescription, false);
    }

    protected static void updateState(DataContext context, String stateDescription, boolean waitingForResources) {
        context.put("stateDescription", stateDescription);
        context.put("waitingForResources", Boolean.toString(waitingForResources));
        context.save();
    }
}
