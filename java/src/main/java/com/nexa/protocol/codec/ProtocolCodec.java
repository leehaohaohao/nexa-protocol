package com.nexa.protocol.codec;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.nexa.protocol.Artifact.ArtifactAck;
import com.nexa.protocol.Artifact.ArtifactChunk;
import com.nexa.protocol.Artifact.ArtifactRequest;
import com.nexa.protocol.Common.MessageType;
import com.nexa.protocol.Disconnect.DisconnectRequest;
import com.nexa.protocol.EnvelopeOuterClass.Envelope;
import com.nexa.protocol.Heartbeat.HeartbeatRequest;
import com.nexa.protocol.Heartbeat.HeartbeatResponse;
import com.nexa.protocol.Query.ContainerLogsRequest;
import com.nexa.protocol.Query.ContainerLogsResponse;
import com.nexa.protocol.Query.ContainerStatusRequest;
import com.nexa.protocol.Query.ContainerStatusResponse;
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

    /**
     * 用指定 request_id 构建 Envelope，供响应回填请求关联使用
     */
    private static Envelope buildEnvelopeWithRequestId(String requestId, MessageType type, byte[] payload,
                                                       String sourceId, String targetId) {
        return buildEnvelope(type, payload, sourceId, targetId).toBuilder()
                .setRequestId(requestId)
                .build();
    }

    // ---- Register (Client) ----

    public static Envelope buildRegisterRequest(String runnerId, String hostname, String ip, String version) {
        return buildRegisterRequest(runnerId, hostname, ip, version, "");
    }

    /**
     * 构建带 token 的注册请求（L1 注册认证）
     */
    public static Envelope buildRegisterRequest(String runnerId, String hostname, String ip, String version, String token) {
        RegisterRequest req = RegisterRequest.newBuilder()
                .setRunnerId(runnerId)
                .setHostname(hostname)
                .setIp(ip)
                .setVersion(version)
                .setToken(token)
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

    // ---- Container Status Query (Master) ----

    public static Envelope buildContainerStatusRequest(String targetId, ContainerStatusRequest req) {
        return buildEnvelope(MessageType.CONTAINER_STATUS_REQ, req.toByteArray(), "master", targetId);
    }

    /**
     * 构建容器状态查询响应（runner → master），回填请求 request_id 供主节点关联
     */
    public static Envelope buildContainerStatusResponse(String requestId, String sourceId, ContainerStatusResponse resp) {
        return buildEnvelopeWithRequestId(requestId, MessageType.CONTAINER_STATUS_RESP,
                resp.toByteArray(), sourceId, "master");
    }

    public static ContainerStatusRequest parseContainerStatusRequest(byte[] payload) throws InvalidProtocolBufferException {
        return ContainerStatusRequest.parseFrom(payload);
    }

    public static ContainerStatusResponse parseContainerStatusResponse(byte[] payload) throws InvalidProtocolBufferException {
        return ContainerStatusResponse.parseFrom(payload);
    }

    // ---- Container Logs Query (Master) ----

    public static Envelope buildContainerLogsRequest(String targetId, ContainerLogsRequest req) {
        return buildEnvelope(MessageType.CONTAINER_LOGS_REQ, req.toByteArray(), "master", targetId);
    }

    /**
     * 构建容器日志查询响应（runner → master），回填请求 request_id 供主节点关联
     */
    public static Envelope buildContainerLogsResponse(String requestId, String sourceId, ContainerLogsResponse resp) {
        return buildEnvelopeWithRequestId(requestId, MessageType.CONTAINER_LOGS_RESP,
                resp.toByteArray(), sourceId, "master");
    }

    public static ContainerLogsRequest parseContainerLogsRequest(byte[] payload) throws InvalidProtocolBufferException {
        return ContainerLogsRequest.parseFrom(payload);
    }

    public static ContainerLogsResponse parseContainerLogsResponse(byte[] payload) throws InvalidProtocolBufferException {
        return ContainerLogsResponse.parseFrom(payload);
    }

    // ---- Artifact Transfer (Client) ----

    public static Envelope buildArtifactRequest(String runnerId, ArtifactRequest req) {
        return buildEnvelope(MessageType.ARTIFACT_REQ, req.toByteArray(), runnerId, "master");
    }

    public static ArtifactRequest parseArtifactRequest(byte[] payload) throws InvalidProtocolBufferException {
        return ArtifactRequest.parseFrom(payload);
    }

    // ---- Artifact Transfer (Master) ----

    /**
     * 构建产物分块（master → runner），回填请求 request_id 作为 transfer_id 关联整次传输
     */
    public static Envelope buildArtifactChunk(String requestId, String targetId, ArtifactChunk chunk) {
        return buildEnvelopeWithRequestId(requestId, MessageType.ARTIFACT_DATA, chunk.toByteArray(), "master", targetId);
    }

    public static ArtifactChunk parseArtifactChunk(byte[] payload) throws InvalidProtocolBufferException {
        return ArtifactChunk.parseFrom(payload);
    }

    // ---- Artifact Ack (Client) ----

    /**
     * 构建产物传输确认（runner → master），回填请求 request_id 供主节点关联
     */
    public static Envelope buildArtifactAck(String requestId, String runnerId, ArtifactAck ack) {
        return buildEnvelopeWithRequestId(requestId, MessageType.ARTIFACT_ACK, ack.toByteArray(), runnerId, "master");
    }

    public static ArtifactAck parseArtifactAck(byte[] payload) throws InvalidProtocolBufferException {
        return ArtifactAck.parseFrom(payload);
    }

    // ---- Envelope 解析 ----

    public static Envelope parseEnvelope(byte[] data) throws InvalidProtocolBufferException {
        return Envelope.parseFrom(data);
    }

    public static byte[] extractPayload(Envelope envelope) {
        return envelope.getPayload().toByteArray();
    }
}
