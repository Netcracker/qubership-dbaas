package com.netcracker.cloud.dbaas.serializer;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializerBase;
import com.netcracker.cloud.dbaas.service.BlueGreenStatusUtil;
import com.netcracker.core.scheduler.po.task.TaskState;

public class TaskStateSerializer extends ToStringSerializerBase {
    public TaskStateSerializer() {
        super(Object.class);
    }

    @Override
    public String valueToString(Object value) {
        return BlueGreenStatusUtil.taskStatusToString((TaskState) value);
    }
}
