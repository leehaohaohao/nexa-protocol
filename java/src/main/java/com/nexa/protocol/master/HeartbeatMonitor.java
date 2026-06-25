package com.nexa.protocol.master;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
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
        scheduler.scheduleAtFixedRate(this::checkTimeouts,
                checkInterval.toMillis(), checkInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    void checkTimeouts() {
        long now = System.currentTimeMillis();
        long timeoutMillis = timeout.toMillis();
        // copy to avoid ConcurrentModificationException when close triggers remove
        for (RunnerSession session : new ArrayList<>(sessionManager.allSessions())) {
            if (now - session.getLastHeartbeatTime() > timeoutMillis) {
                String runnerId = session.getRunnerId();
                log.warn("runner {} heartbeat timeout, closing", runnerId);
                sessionManager.remove(runnerId);
                session.close();
                try {
                    listener.onDisconnect(runnerId, "heartbeat_timeout");
                } catch (Exception e) {
                    log.error("listener.onDisconnect error for {}", runnerId, e);
                }
            }
        }
    }
}
