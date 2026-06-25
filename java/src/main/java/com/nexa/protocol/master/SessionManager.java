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
