package com.nexa.protocol.master;

import com.nexa.protocol.Artifact.ArtifactRequest;
import com.nexa.protocol.Common.MessageType;
import com.nexa.protocol.Disconnect.DisconnectRequest;
import com.nexa.protocol.EnvelopeOuterClass.Envelope;
import com.nexa.protocol.Heartbeat.HeartbeatRequest;
import com.nexa.protocol.Query.ContainerLogsResponse;
import com.nexa.protocol.Query.ContainerStatusResponse;
import com.nexa.protocol.Register.RegisterRequest;
import com.nexa.protocol.Task.TaskResponse;
import com.nexa.protocol.codec.ProtocolCodec;

/**
 * 测试用 Envelope 构造器。
 */
public final class EnvelopeFixtures {

    private EnvelopeFixtures() {
    }

    public static Envelope register(String runnerId, String token) {
        RegisterRequest req = RegisterRequest.newBuilder()
                .setRunnerId(runnerId)
                .setHostname(runnerId + "-host")
                .setIp("127.0.0.1")
                .setVersion("test")
                .setToken(token)
                .build();
        return ProtocolCodec.buildEnvelope(MessageType.REGISTER_REQ, req.toByteArray(), runnerId);
    }

    public static Envelope heartbeat(String runnerId) {
        HeartbeatRequest req = HeartbeatRequest.newBuilder()
                .setRunnerId(runnerId)
                .setRunningTasks(0)
                .build();
        return ProtocolCodec.buildEnvelope(MessageType.HEARTBEAT_REQ, req.toByteArray(), runnerId);
    }

    public static Envelope disconnect(String runnerId, String reason) {
        DisconnectRequest req = DisconnectRequest.newBuilder()
                .setRunnerId(runnerId)
                .setReason(reason)
                .build();
        return ProtocolCodec.buildEnvelope(MessageType.DISCONNECT_REQ, req.toByteArray(), runnerId);
    }

    public static Envelope taskResult(String runnerId, String taskId) {
        TaskResponse resp = TaskResponse.newBuilder()
                .setRunnerId(runnerId)
                .setTaskId(taskId)
                .setSuccess(true)
                .build();
        return ProtocolCodec.buildEnvelope(MessageType.TASK_DISPATCH_RESP, resp.toByteArray(), runnerId);
    }

    public static Envelope containerStatus(String runnerId, boolean running, String status) {
        ContainerStatusResponse resp = ContainerStatusResponse.newBuilder()
                .setRunnerId(runnerId)
                .setRunning(running)
                .setStatus(status)
                .build();
        return ProtocolCodec.buildEnvelope(MessageType.CONTAINER_STATUS_RESP, resp.toByteArray(), runnerId);
    }

    public static Envelope containerLogs(String runnerId, String content) {
        ContainerLogsResponse resp = ContainerLogsResponse.newBuilder()
                .setRunnerId(runnerId)
                .setContent(content)
                .build();
        return ProtocolCodec.buildEnvelope(MessageType.CONTAINER_LOGS_RESP, resp.toByteArray(), runnerId);
    }

    /** source_id 为声明身份，用于校验 channel 绑定与会话选择 */
    public static Envelope artifactRequest(String sourceId, String serviceId) {
        ArtifactRequest req = ArtifactRequest.newBuilder()
                .setServiceId(serviceId)
                .setType("JAR")
                .setVersion(0)
                .build();
        return ProtocolCodec.buildEnvelope(MessageType.ARTIFACT_REQ, req.toByteArray(), sourceId);
    }
}
