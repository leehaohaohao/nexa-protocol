package com.nexa.protocol.master;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class SessionManager {

    private final ConcurrentHashMap<String, RunnerSession> sessions = new ConcurrentHashMap<>();

    public RunnerSession register(RunnerSession session) {
        RunnerSession[] old = {null};
        sessions.compute(session.getRunnerId(), (key, existing) -> {
            if (existing != null) {
                old[0] = existing;
            }
            return session;
        });
        return old[0];
    }

    public RunnerSession remove(String runnerId) {
        return sessions.remove(runnerId);
    }

    /**
     * 仅当当前映射的 session 与期望的 session 相同时才移除
     * 用于避免重连场景下误删新 session
     * @return true 如果成功移除
     */
    public boolean removeIfPresent(String runnerId, RunnerSession expected) {
        return sessions.remove(runnerId, expected);
    }

    public Optional<RunnerSession> get(String runnerId) {
        return Optional.ofNullable(sessions.get(runnerId));
    }

    public Collection<RunnerSession> allSessions() {
        return sessions.values();
    }

    public int size() {
        return sessions.size();
    }
}
