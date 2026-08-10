package com.nexa.protocol.codec;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.nexa.protocol.Common.MessageType;
import com.nexa.protocol.Disconnect.DisconnectRequest;
import com.nexa.protocol.EnvelopeOuterClass.Envelope;
import com.nexa.protocol.Heartbeat.HeartbeatRequest;
import com.nexa.protocol.Heartbeat.HeartbeatResponse;
import com.nexa.protocol.Register.RegisterRequest;
import com.nexa.protocol.Register.RegisterResponse;
import com.nexa.protocol.Task.TaskRequest;
import com.nexa.protocol.Task.TaskResponse;

import java.util.UUID;

public class ProtocolCodec {

    private ProtocolCodec() {
    }

    // ---- Envelope 构建 ----

    public static Envelope buildEnvelope(MessageType type, byte[] payload, String sourceId, String targetId) {
        return Envelope.newBuilder()
                .setVersion(1)
                .setType(type)
                .setRequestId(UUID.randomUUID().toString())
                .setSourceId(sourceId)
                .setTargetId(targetId)
                .setTimestamp(System.currentTimeMillis())
                .setPayload(ByteString.copyFrom(payload))
                .build();
    }

    public static Envelope buildEnvelope(MessageType type, byte[] payload, String sourceId) {
        return buildEnvelope(type, payload, sourceId, "");
    }

    // ---- Register (Client) ----

    public static Envelope buildRegisterRequest(String runnerId, String hostname, String ip, String version) {
        RegisterRequest req = RegisterRequest.newBuilder()
                .setRunnerId(runnerId)
                .setHostname(hostname)
                .setIp(ip)
                .setVersion(version)
                .build();
        return buildEnvelope(MessageType.REGISTER_REQ, req.toByteArray(), runnerId);
    }

    public static RegisterResponse parseRegisterResponse(byte[] payload) throws InvalidProtocolBufferException {
        return RegisterResponse.parseFrom(payload);
    }

    // ---- Heartbeat (Client) ----

    public static Envelope buildHeartbeatRequest(String runnerId, int runningTasks, double cpuUsage, double memoryUsage) {
        HeartbeatRequest req = HeartbeatRequest.newBuilder()
                .setRunnerId(runnerId)
                .setRunningTasks(runningTasks)
                .setCpuUsage(cpuUsage)
                .setMemoryUsage(memoryUsage)
                .build();
        return buildEnvelope(MessageType.HEARTBEAT_REQ, req.toByteArray(), runnerId);
    }

    public static HeartbeatResponse parseHeartbeatResponse(byte[] payload) throws InvalidProtocolBufferException {
        return HeartbeatResponse.parseFrom(payload);
    }

    // ---- Disconnect (Client) ----

    public static Envelope buildDisconnectRequest(String runnerId, String reason) {
        DisconnectRequest req = DisconnectRequest.newBuilder()
                .setRunnerId(runnerId)
                .setReason(reason)
                .build();
        return buildEnvelope(MessageType.DISCONNECT_REQ, req.toByteArray(), runnerId);
    }

    // ---- Register (Master) ----

    public static Envelope buildRegisterResponse(String targetId, boolean success, String message) {
        RegisterResponse resp = RegisterResponse.newBuilder()
                .setSuccess(success)
                .setMessage(message)
                .build();
        return buildEnvelope(MessageType.REGISTER_RESP, resp.toByteArray(), "master", targetId);
    }

    public static RegisterRequest parseRegisterRequest(byte[] payload) throws InvalidProtocolBufferException {
        return RegisterRequest.parseFrom(payload);
    }

    // ---- Heartbeat (Master) ----

    public static Envelope buildHeartbeatResponse(String targetId) {
        HeartbeatResponse resp = HeartbeatResponse.newBuilder().setSuccess(true).build();
        return buildEnvelope(MessageType.HEARTBEAT_RESP, resp.toByteArray(), "master", targetId);
    }

    public static HeartbeatRequest parseHeartbeatRequest(byte[] payload) throws InvalidProtocolBufferException {
        return HeartbeatRequest.parseFrom(payload);
    }

    // ---- Disconnect (Master) ----

    public static DisconnectRequest parseDisconnectRequest(byte[] payload) throws InvalidProtocolBufferException {
        return DisconnectRequest.parseFrom(payload);
    }

    // ---- Task Dispatch (Master) ----

    public static Envelope buildTaskDispatchRequest(String targetId, TaskRequest req) {
        return buildEnvelope(MessageType.TASK_DISPATCH_REQ, req.toByteArray(), "master", targetId);
    }

    public static Envelope buildTaskDispatchResponse(String sourceId, String taskId, boolean success,
                                                     int exitCode, String output, String error) {
        TaskResponse resp = TaskResponse.newBuilder()
                .setTaskId(taskId)
                .setRunnerId(sourceId)
                .setSuccess(success)
                .setExitCode(exitCode)
                .setOutput(output)
                .setError(error)
                .build();
        return buildEnvelope(MessageType.TASK_DISPATCH_RESP, resp.toByteArray(), sourceId, "master");
    }

    public static TaskRequest parseTaskRequest(byte[] payload) throws InvalidProtocolBufferException {
        return TaskRequest.parseFrom(payload);
    }

    public static TaskResponse parseTaskResponse(byte[] payload) throws InvalidProtocolBufferException {
        return TaskResponse.parseFrom(payload);
    }

    // ---- Envelope 解析 ----

    public static Envelope parseEnvelope(byte[] data) throws InvalidProtocolBufferException {
        return Envelope.parseFrom(data);
    }

    public static byte[] extractPayload(Envelope envelope) {
        return envelope.getPayload().toByteArray();
    }
}
