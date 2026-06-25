package com.nexa.protocol.master;

import com.nexa.protocol.EnvelopeOuterClass.Envelope;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class RunnerSession {

    private final String runnerId;
    private final Channel channel;
    private final String hostname;
    private final String ip;
    private final String version;
    private final AtomicLong lastHeartbeatTime;
    private final AtomicBoolean timedOut = new AtomicBoolean(false);

    public RunnerSession(String runnerId, Channel channel, String hostname, String ip, String version) {
        this.runnerId = runnerId;
        this.channel = channel;
        this.hostname = hostname;
        this.ip = ip;
        this.version = version;
        this.lastHeartbeatTime = new AtomicLong(System.currentTimeMillis());
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
        return lastHeartbeatTime.get();
    }

    public void updateHeartbeatTime() {
        // 如果已标记超时，不更新（避免竞态：检测线程刚标记超时，IO线程又更新时间）
        if (!timedOut.get()) {
            lastHeartbeatTime.set(System.currentTimeMillis());
        }
    }

    /**
     * 尝试标记为超时，使用 CAS 保证只标记一次
     * @return true 如果成功标记（之前未被标记），false 如果已被标记
     */
    public boolean markTimedOut() {
        return timedOut.compareAndSet(false, true);
    }

    /**
     * 检查是否已标记超时
     */
    public boolean isTimedOut() {
        return timedOut.get();
    }

    /**
     * 检查是否超时（时间差判断，不修改状态）
     */
    public boolean isExpired(long timeoutMillis) {
        return System.currentTimeMillis() - lastHeartbeatTime.get() > timeoutMillis;
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
