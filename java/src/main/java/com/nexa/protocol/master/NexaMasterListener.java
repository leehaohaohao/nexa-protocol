package com.nexa.protocol.master;

import com.nexa.protocol.Artifact.ArtifactRequest;
import com.nexa.protocol.EnvelopeOuterClass.Envelope;
import com.nexa.protocol.Heartbeat.HeartbeatRequest;
import com.nexa.protocol.Query.ContainerLogsResponse;
import com.nexa.protocol.Query.ContainerStatusResponse;
import com.nexa.protocol.Register.RegisterRequest;
import com.nexa.protocol.Register.RegisterResponse;
import com.nexa.protocol.Task.TaskResponse;

public interface NexaMasterListener {

    RegisterResponse onRegister(RunnerSession session, RegisterRequest req);

    void onHeartbeat(RunnerSession session, HeartbeatRequest req);

    /**
     * 断开事件（旧签名）。
     *
     * <p>只携带 runnerId，无法区分「旧会话迟到断开」与「当前会话断开」；
     * 当同 ID 新连接已接管时，调用方若仅凭 runnerId 查询当前会话会误判。
     * 建议实现 {@link #onDisconnect(RunnerSession, String)}。
     */
    void onDisconnect(String runnerId, String reason);

    /**
     * 断开事件（带会话身份）：仅当该会话确实是被移除的那次会话时回调一次。
     *
     * <p>覆盖心跳超时、主动 DISCONNECT、连接断开三条路径，且三条路径共用
     * 「仅条件移除成功才通知」规则，不会重复通知。业务方可据此判断会话代次，
     * 只失败化属于该次会话的待处理任务与查询。
     *
     * <p>默认实现委托旧签名 {@link #onDisconnect(String, String)}，保持向后兼容。
     */
    default void onDisconnect(RunnerSession session, String reason) {
        onDisconnect(session.getRunnerId(), reason);
    }

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

    /**
     * 产物请求回调（子节点请求产物）。
     * 业务方在回调内查注册表定位产物，并通过 {@code session.send} 分块回发
     * （构造分块时复用 {@code requestEnvelope.getRequestId()} 作为 transfer_id）。
     */
    default void onArtifactRequest(RunnerSession session, Envelope requestEnvelope, ArtifactRequest req) {
    }
}
