package com.nexa.protocol;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.nexa.protocol.Common.MessageType;
import com.nexa.protocol.Disconnect.DisconnectRequest;
import com.nexa.protocol.EnvelopeOuterClass.Envelope;
import com.nexa.protocol.Heartbeat.HeartbeatRequest;
import com.nexa.protocol.Heartbeat.HeartbeatResponse;
import com.nexa.protocol.Register.RegisterRequest;
import com.nexa.protocol.Register.RegisterResponse;

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

    // ---- Register ----

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

    // ---- Heartbeat ----

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

    // ---- Disconnect ----

    public static Envelope buildDisconnectRequest(String runnerId, String reason) {
        DisconnectRequest req = DisconnectRequest.newBuilder()
                .setRunnerId(runnerId)
                .setReason(reason)
                .build();
        return buildEnvelope(MessageType.DISCONNECT_REQ, req.toByteArray(), runnerId);
    }

    // ---- Envelope 解析 ----

    public static Envelope parseEnvelope(byte[] data) throws InvalidProtocolBufferException {
        return Envelope.parseFrom(data);
    }

    public static byte[] extractPayload(Envelope envelope) {
        return envelope.getPayload().toByteArray();
    }
}
