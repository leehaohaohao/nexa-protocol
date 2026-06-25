package com.nexa.protocol.master;

import com.nexa.protocol.Heartbeat.HeartbeatRequest;
import com.nexa.protocol.Register.RegisterRequest;
import com.nexa.protocol.Register.RegisterResponse;

public class NexaMasterListenerAdapter implements NexaMasterListener {

    @Override
    public RegisterResponse onRegister(RunnerSession session, RegisterRequest req) {
        return RegisterResponse.newBuilder()
                .setSuccess(true)
                .setMessage("ok")
                .build();
    }

    @Override
    public void onHeartbeat(RunnerSession session, HeartbeatRequest req) {
    }

    @Override
    public void onDisconnect(String runnerId, String reason) {
    }
}
