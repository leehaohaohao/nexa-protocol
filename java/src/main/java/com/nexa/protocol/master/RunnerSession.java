package com.nexa.protocol.master;

import com.nexa.protocol.EnvelopeOuterClass.Envelope;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;

public class RunnerSession {

    private final String runnerId;
    private final Channel channel;
    private final String hostname;
    private final String ip;
    private final String version;
    private volatile long lastHeartbeatTime;

    public RunnerSession(String runnerId, Channel channel, String hostname, String ip, String version) {
        this.runnerId = runnerId;
        this.channel = channel;
        this.hostname = hostname;
        this.ip = ip;
        this.version = version;
        this.lastHeartbeatTime = System.currentTimeMillis();
    }

    public String getRunnerId() {
        return runnerId;
    }

    public Channel getChannel() {
        return channel;
    }

    public String getHostname() {
        return hostname;
    }

    public String getIp() {
        return ip;
    }

    public String getVersion() {
        return version;
    }

    public long getLastHeartbeatTime() {
        return lastHeartbeatTime;
    }

    public void updateHeartbeatTime() {
        this.lastHeartbeatTime = System.currentTimeMillis();
    }

    public boolean send(Envelope envelope) {
        if (!isActive()) {
            return false;
        }
        channel.writeAndFlush(envelope.toByteArray());
        return true;
    }

    public void send(Envelope envelope, ChannelFutureListener listener) {
        if (isActive()) {
            channel.writeAndFlush(envelope.toByteArray()).addListener(listener);
        }
    }

    public void close() {
        if (isActive()) {
            channel.close();
        }
    }

    public boolean isActive() {
        return channel != null && channel.isActive();
    }
}
