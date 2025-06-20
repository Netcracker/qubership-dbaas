package org.qubership.cloud.dbaas.serializer;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializerBase;
import org.qubership.cloud.dbaas.service.BlueGreenStatusUtil;
import org.qubership.core.scheduler.po.task.TaskState;

public class TaskStateSerializer extends ToStringSerializerBase {
    public TaskStateSerializer() {
        super(Object.class);
    }

    @Override
    public String valueToString(Object value) {
        return BlueGreenStatusUtil.taskStatusToString((TaskState) value);
    }
}
