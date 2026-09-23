package com.nexa.protocol.master;

import com.nexa.protocol.Heartbeat.HeartbeatRequest;
import com.nexa.protocol.Register.RegisterRequest;
import com.nexa.protocol.Register.RegisterResponse;
import com.nexa.protocol.master.netty.MasterChannelHandler;
import com.nexa.protocol.master.netty.handler.MessageDispatcher;
import com.nexa.protocol.master.netty.handler.MessageHandler;
import com.nexa.protocol.master.netty.handler.RegisterHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 超时事件测试（专项修复 B）：只有被确认移除的那次会话才关闭并通知一次；
 * 已被新会话接管的过期旧连接只清理、不通知新节点掉线。
 */
class HeartbeatMonitorTest {

    private static final String TOKEN = "good-token";

    private SessionManager sessionManager;
    private RecordingMasterListener listener;

    @BeforeEach
    void setUp() {
        sessionManager = new SessionManager();
        listener = new RecordingMasterListener(TOKEN);
    }

    /** 超时会话：条件移除成功 → 关闭并通知一次 */
    @Test
    void timeoutRemovesSessionAndNotifiesOnce() {
        EmbeddedChannel channel = newIdleChannel();
        RunnerSession session = new RunnerSession("R", channel, "host", "127.0.0.1", "test");
        sessionManager.register(session);

        monitor().checkTimeouts();

        assertTrue(sessionManager.get("R").isEmpty(), "timed out session should be removed");
        assertTrue(session.isTimedOut());
        assertFalse(channel.isOpen(), "timed out connection should be closed");
        assertEquals(1, listener.countDisconnects("R"));
        assertEquals(List.of("heartbeat_timeout"), listener.disconnectReasons("R"));
    }

    /** 重复扫描不会对同一会话重复通知 */
    @Test
    void repeatedScansNotifyOnlyOnce() {
        EmbeddedChannel channel = newIdleChannel();
        RunnerSession session = new RunnerSession("R", channel, "host", "127.0.0.1", "test");
        sessionManager.register(session);

        HeartbeatMonitor monitor = monitor();
        monitor.checkTimeouts();
        monitor.checkTimeouts();
        monitor.checkTimeouts();

        assertEquals(1, listener.countDisconnects("R"), "timeout must be notified exactly once");
    }

    /** 条件移除失败的过期会话（已被新会话接管）：仍清理旧连接，但不通知 */
    @Test
    void supersededExpiredSessionIsClosedWithoutNotification() {
        EmbeddedChannel oldChannel = newIdleChannel();
        RunnerSession stale = new RunnerSession("R", oldChannel, "host", "127.0.0.1", "test");

        // 注册表把 stale 视为「已被接管」：条件移除必然失败，但仍会被扫描遍历到
        SessionManager supersedingManager = new SessionManager() {
            @Override
            public boolean removeIfPresent(String runnerId, RunnerSession expected) {
                return false;
            }

            @Override
            public Collection<RunnerSession> allSessions() {
                return List.of(stale);
            }
        };

        new HeartbeatMonitor(supersedingManager, listener, alwaysExpired(), Duration.ofSeconds(5))
                .checkTimeouts();

        assertTrue(stale.isTimedOut(), "stale session should still be marked timed out");
        assertFalse(oldChannel.isOpen(), "stale connection should still be closed");
        assertEquals(0, listener.countDisconnects("R"),
                "no disconnect event when the session was already superseded");
    }

    /** 超时与 channelInactive 不得双重通知 */
    @Test
    void timeoutThenChannelInactiveNotifiesOnlyOnce() {
        EmbeddedChannel channel = newRegisteredChannel("R");
        RunnerSession session = sessionManager.get("R").orElseThrow();
        listener.reset();

        monitor().checkTimeouts();
        // 超时已关闭连接并通知一次；随后的 channelInactive 不应再通知
        channel.runPendingTasks();

        assertEquals(1, listener.countDisconnects("R"),
                "timeout + channelInactive must produce exactly one disconnect event");
        assertTrue(sessionManager.get("R").isEmpty());
        assertTrue(session.isTimedOut());
    }

    /** 正常连接断开（未超时）仍应通知一次 connection_lost */
    @Test
    void normalChannelCloseNotifiesConnectionLostOnce() {
        EmbeddedChannel channel = newRegisteredChannel("R");
        listener.reset();

        channel.close();
        channel.runPendingTasks();

        assertEquals(1, listener.countDisconnects("R"));
        assertEquals(List.of("connection_lost"), listener.disconnectReasons("R"));
        assertTrue(sessionManager.get("R").isEmpty());
    }

    /** 旧 API 兼容：只实现旧签名的 listener 仍能收到断开事件（新签名默认委托） */
    @Test
    void legacyListenerStillReceivesDisconnectEvents() {
        List<String> legacyEvents = Collections.synchronizedList(new ArrayList<>());
        NexaMasterListener legacy = new NexaMasterListener() {
            @Override
            public RegisterResponse onRegister(RunnerSession session, RegisterRequest req) {
                return RegisterResponse.newBuilder().setSuccess(true).setMessage("ok").build();
            }

            @Override
            public void onHeartbeat(RunnerSession session, HeartbeatRequest req) {
            }

            @Override
            public void onDisconnect(String runnerId, String reason) {
                legacyEvents.add(runnerId + ":" + reason);
            }
        };

        EmbeddedChannel channel = newIdleChannel();
        RunnerSession session = new RunnerSession("R", channel, "host", "127.0.0.1", "test");
        sessionManager.register(session);

        new HeartbeatMonitor(sessionManager, legacy, alwaysExpired(), Duration.ofSeconds(5)).checkTimeouts();

        assertEquals(List.of("R:heartbeat_timeout"), legacyEvents,
                "legacy onDisconnect(String, String) must still be invoked via the default delegation");
    }

    /**
     * 并发交错：超时扫描与新连接接管同时发生。
     *
     * <p>用真实超时语义（旧会话被老化、新会话新鲜），验证不变量：
     * R 的断开通知不超过 1 次；新会话绝不会被以自身身份通知掉线。
     */
    @Test
    void concurrentTakeoverDuringTimeoutScanKeepsInvariants() throws Exception {
        for (int round = 0; round < 200; round++) {
            SessionManager manager = new SessionManager();
            RecordingMasterListener events = new RecordingMasterListener(TOKEN);

            EmbeddedChannel oldChannel = newIdleChannel();
            RunnerSession stale = new RunnerSession("R", oldChannel, "host", "127.0.0.1", "test");
            ageHeartbeat(stale, 60_000); // 旧会话已过期
            manager.register(stale);

            EmbeddedChannel newChannel = newIdleChannel();
            // 新会话刚创建，心跳时间为当前时刻：在 1 秒超时下不会过期
            RunnerSession fresh = new RunnerSession("R", newChannel, "host", "127.0.0.1", "test");

            HeartbeatMonitor monitor = new HeartbeatMonitor(
                    manager, events, Duration.ofSeconds(1), Duration.ofSeconds(5));

            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);

            Thread scanner = new Thread(() -> {
                await(start);
                try {
                    monitor.checkTimeouts();
                } finally {
                    done.countDown();
                }
            });
            Thread takeover = new Thread(() -> {
                await(start);
                try {
                    manager.register(fresh);
                } finally {
                    done.countDown();
                }
            });

            scanner.start();
            takeover.start();
            start.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS), "round " + round + " did not finish in time");

            List<RecordingMasterListener.DisconnectEvent> disconnectEvents = events.disconnectEvents();
            assertTrue(disconnectEvents.size() <= 1,
                    "round " + round + ": at most one disconnect event expected, got " + disconnectEvents.size());

            for (RecordingMasterListener.DisconnectEvent event : disconnectEvents) {
                assertNotSame(fresh, event.session(),
                        "round " + round + ": fresh session must never be reported as disconnected");
                assertSame(stale, event.session(),
                        "round " + round + ": only the stale session may be reported");
            }
        }
    }

    // ---- helpers ----

    /** 负超时使任何会话都被判定过期，避免依赖 sleep（单会话确定性用例使用） */
    private static Duration alwaysExpired() {
        return Duration.ofMillis(-1000);
    }

    /** 将会话的心跳时间拨回过去，用于构造「旧会话过期、新会话新鲜」的交错场景 */
    private static void ageHeartbeat(RunnerSession session, long millis) throws Exception {
        Field field = RunnerSession.class.getDeclaredField("lastHeartbeatTime");
        field.setAccessible(true);
        ((AtomicLong) field.get(session)).set(System.currentTimeMillis() - millis);
    }

    private HeartbeatMonitor monitor() {
        return new HeartbeatMonitor(sessionManager, listener, alwaysExpired(), Duration.ofSeconds(5));
    }

    /** 无 pipeline 的空闲连接，close() 不会触发业务处理 */
    private EmbeddedChannel newIdleChannel() {
        return new EmbeddedChannel();
    }

    /** 带注册链路的连接，注册成功并消费响应，使 channelInactive 语义生效 */
    private EmbeddedChannel newRegisteredChannel(String runnerId) {
        MessageDispatcher dispatcher = new MessageDispatcher(
                List.<MessageHandler>of(new RegisterHandler(sessionManager, listener)));
        MasterChannelHandler masterHandler = new MasterChannelHandler(sessionManager, listener, dispatcher);
        EmbeddedChannel channel = new EmbeddedChannel(masterHandler);

        channel.writeInbound(EnvelopeFixtures.register(runnerId, TOKEN).toByteArray());
        channel.runPendingTasks();
        channel.readOutbound();

        assertTrue(sessionManager.get(runnerId).isPresent(), "session should be registered: " + runnerId);
        return channel;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
