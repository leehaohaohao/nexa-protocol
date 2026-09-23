package com.nexa.protocol.master;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class HeartbeatMonitor {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatMonitor.class);

    private final SessionManager sessionManager;
    private final NexaMasterListener listener;
    private final Duration timeout;
    private final Duration checkInterval;
    private ScheduledExecutorService scheduler;

    public HeartbeatMonitor(SessionManager sessionManager, NexaMasterListener listener,
                            Duration timeout, Duration checkInterval) {
        this.sessionManager = sessionManager;
        this.listener = listener;
        this.timeout = timeout;
        this.checkInterval = checkInterval;
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nexa-heartbeat-monitor");
            t.setDaemon(true);
            return t;
        });
        // 使用 scheduleWithFixedDelay：每轮检查完成后再等待 checkInterval，避免任务堆积
        scheduler.scheduleWithFixedDelay(this::checkTimeouts,
                checkInterval.toMillis(), checkInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    void checkTimeouts() {
        try {
            doCheck();
        } catch (Exception e) {
            // 顶层异常保护：防止未捕获异常导致 ScheduledExecutorService 停止调度
            log.error("heartbeat check failed", e);
        }
    }

    // TODO [P1-7] 当前为 O(N) 全量扫描，大规模场景（数千+ Runner）可优化：
    //  方案一：DelayQueue<RunnerSession>，session 实现 Delayed 接口，poll 到期即超时，O(logN)
    //  方案二：时间轮 HashedWheelTimer，每个 session 注册一个 timeout task，O(1)
    //  优化时只需替换 doCheck() 实现，上层 checkTimeouts() 的异常保护不受影响
    private void doCheck() {
        long timeoutMillis = timeout.toMillis();

        for (RunnerSession session : sessionManager.allSessions()) {
            // 跳过已标记超时的 session
            if (session.isTimedOut()) {
                continue;
            }

            // 时间差判断
            if (!session.isExpired(timeoutMillis)) {
                continue;
            }

            String runnerId = session.getRunnerId();

            // CAS 标记超时，赢得处理权（保证同一会话只被处理一次）
            if (!session.markTimedOut()) {
                continue;
            }

            // 先条件移除：仅当注册表中当前绑定的仍是这个过期会话时，本次才拥有通知权。
            // 若已被同 ID 新连接接管，则不通知（新节点保持在线），只清理过期旧连接。
            boolean removedCurrent = sessionManager.removeIfPresent(runnerId, session);

            log.warn("runner {} heartbeat timeout, closing (current session: {})", runnerId, removedCurrent);

            session.close();

            if (removedCurrent) {
                try {
                    listener.onDisconnect(session, "heartbeat_timeout");
                } catch (Exception e) {
                    log.error("listener.onDisconnect error for {}", runnerId, e);
                }
            }
        }
    }
}
