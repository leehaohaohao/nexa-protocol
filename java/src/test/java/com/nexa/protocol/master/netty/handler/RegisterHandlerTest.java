package com.nexa.protocol.master.netty.handler;

import com.nexa.protocol.Common.MessageType;
import com.nexa.protocol.EnvelopeOuterClass.Envelope;
import com.nexa.protocol.Heartbeat.HeartbeatRequest;
import com.nexa.protocol.Register.RegisterRequest;
import com.nexa.protocol.Register.RegisterResponse;
import com.nexa.protocol.codec.ProtocolCodec;
import com.nexa.protocol.master.NexaMasterListener;
import com.nexa.protocol.master.RunnerSession;
import com.nexa.protocol.master.SessionManager;
import com.nexa.protocol.master.netty.MasterChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.AttributeKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 注册接管语义测试：验证「先认证、后接管」以及旧连接断开不误报离线。
 */
class RegisterHandlerTest {

    /** 与 RegisterHandler / MasterChannelHandler 内部使用的同名 AttributeKey 等价（AttributeKey 按名称池化） */
    private static final AttributeKey<String> RUNNER_ID_ATTR = AttributeKey.valueOf("nexa.runnerId");

    private SessionManager sessionManager;
    private StubListener listener;

    @BeforeEach
    void setUp() {
        sessionManager = new SessionManager();
        listener = new StubListener();
    }

    /** 认证失败（错误 token）不得踢掉已在线的同 ID 合法节点 */
    @Test
    void rejectedRegisterDoesNotTouchExistingSession() {
        EmbeddedChannel legit = newMasterChannel();
        legit.writeInbound(registerEnvelope("runner-1", "good-token", "host-1").toByteArray());
        legit.runPendingTasks();

        RunnerSession online = sessionManager.get("runner-1").orElseThrow();
        assertSame(legit, online.getChannel());
        assertTrue(online.isActive());
        // 自校验：成功注册的连接确实绑定了身份，证明本测试使用的 AttributeKey 与实现一致
        assertEquals("runner-1", legit.attr(RUNNER_ID_ATTR).get());

        // 攻击连接：同 runnerId 但 token 错误
        EmbeddedChannel attacker = newMasterChannel();
        attacker.writeInbound(registerEnvelope("runner-1", "bad-token", "host-evil").toByteArray());
        attacker.runPendingTasks();

        // 合法会话必须保持在线且未被替换
        RunnerSession still = sessionManager.get("runner-1").orElseThrow();
        assertSame(online, still, "rejected register must not replace the online session");
        assertTrue(still.isActive(), "rejected register must not close the online session");

        // 拒绝连接不得绑定会话身份，因此其断开不会被判定为该节点离线
        assertNull(attacker.attr(RUNNER_ID_ATTR).get(), "rejected connection must not be bound to a runner id");

        // 拒绝原因应先发出再关闭连接
        Object outbound = attacker.readOutbound();
        assertNotNull(outbound, "rejection response should be written before closing");
        assertFalse(attacker.isOpen(), "rejected connection should be closed");

        assertEquals(0, listener.disconnectCount, "rejected register must not report the runner offline");
    }

    /** 合法重连只保留新会话，旧连接断开不误报离线 */
    @Test
    void legitReconnectKeepsOnlyNewSession() {
        EmbeddedChannel oldChannel = newMasterChannel();
        oldChannel.writeInbound(registerEnvelope("runner-2", "good-token", "host-2").toByteArray());
        oldChannel.runPendingTasks();
        RunnerSession oldSession = sessionManager.get("runner-2").orElseThrow();

        EmbeddedChannel newChannel = newMasterChannel();
        newChannel.writeInbound(registerEnvelope("runner-2", "good-token", "host-2").toByteArray());
        newChannel.runPendingTasks();
        RunnerSession newSession = sessionManager.get("runner-2").orElseThrow();

        // 注册表保留新会话
        assertSame(newChannel, newSession.getChannel(), "registry should hold the new session");
        assertSame(newSession, sessionManager.get("runner-2").orElseThrow());
        assertTrue(newSession.isActive());

        // 旧连接被关闭，且其断开不得把新会话标记离线
        assertFalse(oldChannel.isOpen(), "old connection should be closed on takeover");
        assertEquals(0, listener.disconnectCount,
                "old connection close must not report the (still online) runner as offline");
        assertSame(newSession, sessionManager.get("runner-2").orElseThrow(),
                "old connection close must not remove the new session");
        assertFalse(oldSession.isActive());
    }

    /** 同一连接重复注册不应关闭自身 */
    @Test
    void repeatedRegisterOnSameChannelKeepsChannelOpen() {
        EmbeddedChannel channel = newMasterChannel();
        channel.writeInbound(registerEnvelope("runner-3", "good-token", "host-3").toByteArray());
        channel.runPendingTasks();

        channel.writeInbound(registerEnvelope("runner-3", "good-token", "host-3").toByteArray());
        channel.runPendingTasks();

        assertTrue(channel.isOpen(), "re-registering on the same channel must not close it");
        assertTrue(sessionManager.get("runner-3").isPresent());
        assertEquals(1, sessionManager.size(), "only one session should remain registered");
    }

    /** 缺少 runner_id 的注册请求应被拒绝且不注册 */
    @Test
    void registerWithoutRunnerIdIsRejected() {
        EmbeddedChannel channel = newMasterChannel();
        channel.writeInbound(registerEnvelope("", "good-token", "host-4").toByteArray());
        channel.runPendingTasks();

        assertEquals(0, sessionManager.size(), "request without runner_id must not register a session");
        assertFalse(channel.isOpen(), "invalid register request should close the connection");
    }

    /** 认证异常不应导致会话被接管 */
    @Test
    void listenerExceptionDoesNotTakeOverSession() {
        EmbeddedChannel channel = newMasterChannel();
        listener.throwOnRegister = true;
        channel.writeInbound(registerEnvelope("runner-5", "good-token", "host-5").toByteArray());
        channel.runPendingTasks();

        assertEquals(0, sessionManager.size(), "failed authentication must not register a session");
        assertFalse(channel.isOpen(), "failed authentication should close the connection");
    }

    // ---- helpers ----

    /** 构建带完整 Master 处理链的通道，使 channelInactive 语义一并生效 */
    private EmbeddedChannel newMasterChannel() {
        MessageDispatcher dispatcher = new MessageDispatcher(List.of(new RegisterHandler(sessionManager, listener)));
        MasterChannelHandler masterHandler = new MasterChannelHandler(sessionManager, listener, dispatcher);
        return new EmbeddedChannel(masterHandler);
    }

    private Envelope registerEnvelope(String runnerId, String token, String hostname) {
        RegisterRequest req = RegisterRequest.newBuilder()
                .setRunnerId(runnerId)
                .setHostname(hostname)
                .setIp("127.0.0.1")
                .setVersion("test")
                .setToken(token)
                .build();
        return ProtocolCodec.buildEnvelope(MessageType.REGISTER_REQ, req.toByteArray(), runnerId);
    }

    /** 以 token 白名单模拟真实的主节点认证逻辑 */
    private static class StubListener implements NexaMasterListener {

        private static final Set<String> VALID_TOKENS = Set.of("good-token");

        boolean throwOnRegister = false;
        int disconnectCount = 0;
        final List<String> disconnectReasons = new ArrayList<>();

        @Override
        public RegisterResponse onRegister(RunnerSession session, RegisterRequest req) {
            if (throwOnRegister) {
                throw new IllegalStateException("auth backend unavailable");
            }
            boolean ok = VALID_TOKENS.contains(req.getToken());
            return RegisterResponse.newBuilder()
                    .setSuccess(ok)
                    .setMessage(ok ? "ok" : "invalid token")
                    .build();
        }

        @Override
        public void onHeartbeat(RunnerSession session, HeartbeatRequest req) {
        }

        @Override
        public void onDisconnect(String runnerId, String reason) {
            disconnectCount++;
            disconnectReasons.add(reason);
        }
    }
}
