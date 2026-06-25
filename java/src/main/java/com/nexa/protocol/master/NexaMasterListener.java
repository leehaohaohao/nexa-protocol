package com.nexa.protocol.master;

import com.nexa.protocol.Heartbeat.HeartbeatRequest;
import com.nexa.protocol.Register.RegisterRequest;
import com.nexa.protocol.Register.RegisterResponse;

public interface NexaMasterListener {

    RegisterResponse onRegister(RunnerSession session, RegisterRequest req);

    void onHeartbeat(RunnerSession session, HeartbeatRequest req);

    void onDisconnect(String runnerId, String reason);
}
