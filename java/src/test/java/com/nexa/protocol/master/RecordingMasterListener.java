package com.nexa.protocol.master;

import com.nexa.protocol.Artifact.ArtifactRequest;
import com.nexa.protocol.EnvelopeOuterClass.Envelope;
import com.nexa.protocol.Heartbeat.HeartbeatRequest;
import com.nexa.protocol.Query.ContainerLogsResponse;
import com.nexa.protocol.Query.ContainerStatusResponse;
import com.nexa.protocol.Register.RegisterRequest;
import com.nexa.protocol.Register.RegisterResponse;
import com.nexa.protocol.Task.TaskResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 记录型 listener：按<b>会话身份</b>记录每次业务回调，用于断言"业务层未被以他人身份调用"。
 */
public class RecordingMasterListener implements NexaMasterListener {

    /** 断开事件，session 为协议层传入的会话身份 */
    public record DisconnectEvent(String runnerId, String reason, RunnerSession session) {
    }

    private final Set<String> validTokens;

    public final List<String> heartbeats = Collections.synchronizedList(new ArrayList<>());
    public final List<String> taskResults = Collections.synchronizedList(new ArrayList<>());
    public final List<String> containerStatuses = Collections.synchronizedList(new ArrayList<>());
    public final List<String> containerLogs = Collections.synchronizedList(new ArrayList<>());
    public final List<String> artifactRequests = Collections.synchronizedList(new ArrayList<>());
    public final List<DisconnectEvent> disconnects = Collections.synchronizedList(new ArrayList<>());

    public RecordingMasterListener(String... validTokens) {
        this.validTokens = Set.of(validTokens);
    }

    @Override
    public RegisterResponse onRegister(RunnerSession session, RegisterRequest req) {
        boolean ok = validTokens.contains(req.getToken());
        return RegisterResponse.newBuilder()
                .setSuccess(ok)
                .setMessage(ok ? "ok" : "invalid token")
                .build();
    }

    @Override
    public void onHeartbeat(RunnerSession session, HeartbeatRequest req) {
        heartbeats.add(session.getRunnerId());
    }

    /** 旧签名：记录为不带会话身份的事件（协议层不会直接调用它，见 onDisconnect(RunnerSession, String)） */
    @Override
    public void onDisconnect(String runnerId, String reason) {
        disconnects.add(new DisconnectEvent(runnerId, reason, null));
    }

    @Override
    public void onDisconnect(RunnerSession session, String reason) {
        disconnects.add(new DisconnectEvent(session.getRunnerId(), reason, session));
    }

    @Override
    public void onTaskResult(RunnerSession session, TaskResponse resp) {
        taskResults.add(session.getRunnerId());
    }

    @Override
    public void onContainerStatus(RunnerSession session, ContainerStatusResponse resp) {
        containerStatuses.add(session.getRunnerId());
    }

    @Override
    public void onContainerLogs(RunnerSession session, ContainerLogsResponse resp) {
        containerLogs.add(session.getRunnerId());
    }

    @Override
    public void onArtifactRequest(RunnerSession session, Envelope requestEnvelope, ArtifactRequest req) {
        artifactRequests.add(session.getRunnerId());
    }

    // ---- 断言辅助 ----

    public int countDisconnects(String runnerId) {
        synchronized (disconnects) {
            return (int) disconnects.stream()
                    .filter(event -> runnerId.equals(event.runnerId()))
                    .count();
        }
    }

    public List<String> disconnectRunnerIds() {
        synchronized (disconnects) {
            return disconnects.stream().map(DisconnectEvent::runnerId).toList();
        }
    }

    /** 断开事件快照（并发测试中安全遍历） */
    public List<DisconnectEvent> disconnectEvents() {
        synchronized (disconnects) {
            return List.copyOf(disconnects);
        }
    }

    public List<String> disconnectReasons(String runnerId) {
        synchronized (disconnects) {
            return disconnects.stream()
                    .filter(event -> runnerId.equals(event.runnerId()))
                    .map(DisconnectEvent::reason)
                    .toList();
        }
    }

    public void reset() {
        heartbeats.clear();
        taskResults.clear();
        containerStatuses.clear();
        containerLogs.clear();
        artifactRequests.clear();
        disconnects.clear();
    }
}
