package com.nexa.protocol.master.netty.handler;

import com.nexa.protocol.master.EnvelopeFixtures;
import com.nexa.protocol.master.RecordingMasterListener;
import com.nexa.protocol.master.RunnerSession;
import com.nexa.protocol.master.SessionManager;
import com.nexa.protocol.master.netty.MasterChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 协议侧身份校验测试（专项修复 A）：
 * 所有已注册消息必须以「发送 channel 绑定的会话」为准，报文 runnerId / source_id 仅作一致性检查。
 */
class SessionIdentityTest {

    private static final String TOKEN = "good-token";

    private SessionManager sessionManager;
    private RecordingMasterListener listener;

    @BeforeEach
    void setUp() {
        sessionManager = new SessionManager();
        listener = new RecordingMasterListener(TOKEN);
    }

    // ---- 心跳 ----

    /** 未注册连接冒用已在线节点身份发心跳：不刷新任何会话、不回包、不回调 */
    @Test
    void unregisteredConnectionCannotRefreshHeartbeat() {
        EmbeddedChannel online = newMasterChannel();
        register(online, "B");
        RunnerSession sessionB = sessionManager.get("B").orElseThrow();
        long before = sessionB.getLastHeartbeatTime();

        EmbeddedChannel rogue = newMasterChannel();
        rogue.writeInbound(EnvelopeFixtures.heartbeat("B").toByteArray());
        rogue.runPendingTasks();

        assertEquals(before, sessionB.getLastHeartbeatTime(),
                "unregistered heartbeat must not refresh session B");
        assertTrue(listener.heartbeats.isEmpty(), "unregistered heartbeat must not reach business listener");
        assertNull(rogue.readOutbound(), "unregistered connection must not receive a heartbeat response");
        assertSame(sessionB, sessionManager.get("B").orElseThrow());
    }

    /** 已注册 A 冒用 B 的 runnerId 发心跳：B 与 A 的心跳时间都不变，且不回包 */
    @Test
    void registeredConnectionCannotHeartbeatAsAnotherRunner() {
        EmbeddedChannel channelA = newMasterChannel();
        register(channelA, "A");
        EmbeddedChannel channelB = newMasterChannel();
        register(channelB, "B");

        RunnerSession sessionA = sessionManager.get("A").orElseThrow();
        RunnerSession sessionB = sessionManager.get("B").orElseThrow();
        long beforeA = sessionA.getLastHeartbeatTime();
        long beforeB = sessionB.getLastHeartbeatTime();

        channelA.writeInbound(EnvelopeFixtures.heartbeat("B").toByteArray());
        channelA.runPendingTasks();

        assertEquals(beforeB, sessionB.getLastHeartbeatTime(), "forged heartbeat must not refresh B");
        assertEquals(beforeA, sessionA.getLastHeartbeatTime(), "forged heartbeat must not refresh A either");
        assertTrue(listener.heartbeats.isEmpty(), "forged heartbeat must not reach business listener");
        assertNull(channelA.readOutbound(), "forged heartbeat must not be answered");
        assertNull(channelB.readOutbound(), "B must not receive a heartbeat response for A's forgery");
    }

    /** 正常心跳：刷新自身会话并只回发给发送方 */
    @Test
    void normalHeartbeatRefreshesOwnSessionAndRepliesToSender() {
        EmbeddedChannel channel = newMasterChannel();
        register(channel, "B");
        RunnerSession sessionB = sessionManager.get("B").orElseThrow();

        channel.writeInbound(EnvelopeFixtures.heartbeat("B").toByteArray());
        channel.runPendingTasks();

        assertEquals(List.of("B"), listener.heartbeats);
        assertNotNull(channel.readOutbound(), "sender should receive the heartbeat response");
        assertFalse(sessionB.isTimedOut());
    }

    // ---- 主动断开 ----

    /** 未注册连接冒用 B 身份发 DISCONNECT：B 会话不受影响，业务层不收到 B 的断开 */
    @Test
    void unregisteredConnectionCannotDisconnectRunner() {
        EmbeddedChannel online = newMasterChannel();
        register(online, "B");
        RunnerSession sessionB = sessionManager.get("B").orElseThrow();
        listener.reset();

        EmbeddedChannel rogue = newMasterChannel();
        rogue.writeInbound(EnvelopeFixtures.disconnect("B", "bye").toByteArray());
        rogue.runPendingTasks();

        assertSame(sessionB, sessionManager.get("B").orElseThrow(), "B session must survive forged disconnect");
        assertTrue(sessionB.isActive());
        assertEquals(0, listener.countDisconnects("B"), "business listener must not see B disconnect");
        assertFalse(rogue.isOpen(), "unregistered connection should be closed");
    }

    /** 已注册 A 冒用 B 身份发 DISCONNECT：只影响 A 自己，绝不移除 B 的会话 */
    @Test
    void registeredConnectionCannotDisconnectAnotherRunner() {
        EmbeddedChannel channelA = newMasterChannel();
        register(channelA, "A");
        EmbeddedChannel channelB = newMasterChannel();
        register(channelB, "B");
        RunnerSession sessionB = sessionManager.get("B").orElseThrow();
        listener.reset();

        channelA.writeInbound(EnvelopeFixtures.disconnect("B", "bye").toByteArray());
        channelA.runPendingTasks();

        assertSame(sessionB, sessionManager.get("B").orElseThrow(), "forged disconnect must not remove B");
        assertTrue(sessionB.isActive());
        assertEquals(0, listener.countDisconnects("B"));
        // A 自己的连接被拒绝关闭，其会话按正常断线清理
        assertFalse(channelA.isOpen());
        assertTrue(sessionManager.get("A").isEmpty(), "A's own session is cleaned up on its close");
    }

    /** 正常主动断开：条件移除自身会话并通知一次 */
    @Test
    void normalDisconnectRemovesOwnSessionAndNotifiesOnce() {
        EmbeddedChannel channel = newMasterChannel();
        register(channel, "A");
        listener.reset();

        channel.writeInbound(EnvelopeFixtures.disconnect("A", "shutdown").toByteArray());
        channel.runPendingTasks();

        assertTrue(sessionManager.get("A").isEmpty(), "session should be removed on normal disconnect");
        assertEquals(1, listener.countDisconnects("A"));
        assertEquals(List.of("shutdown"), listener.disconnectReasons("A"));
        assertFalse(channel.isOpen());
    }

    // ---- 回执类 ----

    /** 已注册 A 冒用 B 身份发任务回执：业务层不以 B 身份处理 */
    @Test
    void registeredConnectionCannotForgeTaskResult() {
        EmbeddedChannel channelA = newMasterChannel();
        register(channelA, "A");
        EmbeddedChannel channelB = newMasterChannel();
        register(channelB, "B");
        listener.reset();

        channelA.writeInbound(EnvelopeFixtures.taskResult("B", "task-1").toByteArray());
        channelA.runPendingTasks();

        assertTrue(listener.taskResults.isEmpty(), "forged task result must not be processed as B");
    }

    /** 已注册 A 冒用 B 身份发状态/日志回执：业务层不以 B 身份处理 */
    @Test
    void registeredConnectionCannotForgeContainerQueryResults() {
        EmbeddedChannel channelA = newMasterChannel();
        register(channelA, "A");
        EmbeddedChannel channelB = newMasterChannel();
        register(channelB, "B");
        listener.reset();

        channelA.writeInbound(EnvelopeFixtures.containerStatus("B", true, "running").toByteArray());
        channelA.writeInbound(EnvelopeFixtures.containerLogs("B", "logs").toByteArray());
        channelA.runPendingTasks();

        assertTrue(listener.containerStatuses.isEmpty(), "forged status must not be processed as B");
        assertTrue(listener.containerLogs.isEmpty(), "forged logs must not be processed as B");
    }

    /** 已注册 A 用 B 的 source_id 请求产物：按 channel 绑定识别为 A，不以 B 身份回调 */
    @Test
    void registeredConnectionCannotForgeArtifactRequestIdentity() {
        EmbeddedChannel channelA = newMasterChannel();
        register(channelA, "A");
        EmbeddedChannel channelB = newMasterChannel();
        register(channelB, "B");
        listener.reset();

        // source_id 声明 B，但发送方是 A 的连接
        channelA.writeInbound(EnvelopeFixtures.artifactRequest("B", "svc-1").toByteArray());
        channelA.runPendingTasks();

        assertTrue(listener.artifactRequests.isEmpty(),
                "artifact request with mismatched source_id must be rejected");
    }

    /** 正常产物请求：按发送 channel 绑定识别身份 */
    @Test
    void normalArtifactRequestIsAcceptedForOwningSession() {
        EmbeddedChannel channel = newMasterChannel();
        register(channel, "A");
        listener.reset();

        channel.writeInbound(EnvelopeFixtures.artifactRequest("A", "svc-1").toByteArray());
        channel.runPendingTasks();

        assertEquals(List.of("A"), listener.artifactRequests);
    }

    // ---- 接管后的旧连接 ----

    /** 旧连接被同 ID 新连接接管后继续发送已注册消息：一律拒绝，新会话不受影响 */
    @Test
    void supersededConnectionMessagesAreRejected() {
        EmbeddedChannel oldChannel = newMasterChannel();
        register(oldChannel, "R");

        EmbeddedChannel newChannel = newMasterChannel();
        // 手工构造「新连接已接管、旧连接尚未关闭」的交错状态
        RunnerSession superseding = new RunnerSession("R", newChannel, "host", "127.0.0.1", "test");
        sessionManager.register(superseding);
        SessionResolver.bind(newChannel, "R");
        listener.reset();

        assertTrue(oldChannel.isOpen(), "precondition: old connection still open but superseded");
        assertSame(superseding, sessionManager.get("R").orElseThrow(),
                "precondition: registry already points to the superseding session");

        long before = superseding.getLastHeartbeatTime();

        oldChannel.writeInbound(EnvelopeFixtures.heartbeat("R").toByteArray());
        oldChannel.writeInbound(EnvelopeFixtures.taskResult("R", "task-1").toByteArray());
        oldChannel.writeInbound(EnvelopeFixtures.disconnect("R", "bye").toByteArray());
        oldChannel.runPendingTasks();

        assertTrue(listener.heartbeats.isEmpty(), "superseded connection heartbeat must be rejected");
        assertTrue(listener.taskResults.isEmpty(), "superseded connection result must be rejected");
        assertEquals(0, listener.countDisconnects("R"), "superseded connection must not disconnect R");
        assertEquals(before, superseding.getLastHeartbeatTime(), "new session must not be refreshed by old connection");
        assertSame(superseding, sessionManager.get("R").orElseThrow(), "new session must stay registered");
        assertNull(newChannel.readOutbound(), "no response should be sent to the superseded connection");
    }

    /** 接管后新会话一切正常 */
    @Test
    void supersedingSessionKeepsWorking() {
        EmbeddedChannel oldChannel = newMasterChannel();
        register(oldChannel, "R");
        EmbeddedChannel newChannel = newMasterChannel();
        register(newChannel, "R");

        assertFalse(oldChannel.isOpen(), "old connection should be closed by takeover");
        RunnerSession current = sessionManager.get("R").orElseThrow();
        assertSame(newChannel, current.getChannel());

        listener.reset();
        newChannel.writeInbound(EnvelopeFixtures.heartbeat("R").toByteArray());
        newChannel.runPendingTasks();

        assertEquals(List.of("R"), listener.heartbeats, "new session should keep heartbeating");
        assertNotNull(newChannel.readOutbound());
    }

    /** 旧连接关闭不得误报新会话离线 */
    @Test
    void oldConnectionCloseDoesNotReportNewSessionOffline() {
        EmbeddedChannel oldChannel = newMasterChannel();
        register(oldChannel, "R");
        EmbeddedChannel newChannel = newMasterChannel();
        register(newChannel, "R");
        listener.reset();

        assertEquals(0, listener.countDisconnects("R"),
                "closing the superseded connection must not report R offline");
        assertTrue(sessionManager.get("R").orElseThrow().isActive());
    }

    // ---- helpers ----

    private EmbeddedChannel newMasterChannel() {
        MessageDispatcher dispatcher = new MessageDispatcher(List.of(
                new RegisterHandler(sessionManager, listener),
                new HeartbeatHandler(sessionManager, listener),
                new DisconnectHandler(sessionManager, listener),
                new TaskResultHandler(sessionManager, listener),
                new ContainerStatusHandler(sessionManager, listener),
                new ContainerLogsHandler(sessionManager, listener),
                new ArtifactHandler(sessionManager, listener)));
        MasterChannelHandler masterHandler = new MasterChannelHandler(sessionManager, listener, dispatcher);
        return new EmbeddedChannel(masterHandler);
    }

    private void register(EmbeddedChannel channel, String runnerId) {
        channel.writeInbound(EnvelopeFixtures.register(runnerId, TOKEN).toByteArray());
        channel.runPendingTasks();
        // 消费注册响应，便于后续对 outbound 的断言
        Object registerResponse = channel.readOutbound();
        assertNotNull(registerResponse, "register response expected for " + runnerId);
        assertTrue(sessionManager.get(runnerId).isPresent(), "session should be registered: " + runnerId);
    }
}
