package com.nexa.protocol.master;

import com.nexa.protocol.Heartbeat.HeartbeatRequest;
import com.nexa.protocol.Query.ContainerLogsResponse;
import com.nexa.protocol.Query.ContainerStatusResponse;
import com.nexa.protocol.Register.RegisterRequest;
import com.nexa.protocol.Register.RegisterResponse;
import com.nexa.protocol.Task.TaskResponse;

public interface NexaMasterListener {

    RegisterResponse onRegister(RunnerSession session, RegisterRequest req);

    void onHeartbeat(RunnerSession session, HeartbeatRequest req);

    void onDisconnect(String runnerId, String reason);

    /**
     * 任务回执回调，子节点执行结果回传
     */
    default void onTaskResult(RunnerSession session, TaskResponse resp) {
    }

    /**
     * 容器状态查询回执回调，子节点响应状态查询
     */
    default void onContainerStatus(RunnerSession session, ContainerStatusResponse resp) {
    }

    /**
     * 容器日志查询回执回调，子节点响应日志查询
     */
    default void onContainerLogs(RunnerSession session, ContainerLogsResponse resp) {
    }
}
